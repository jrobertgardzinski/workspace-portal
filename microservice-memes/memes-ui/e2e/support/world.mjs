import { AfterAll, BeforeAll, Before, After, setWorldConstructor, setDefaultTimeout } from '@cucumber/cucumber';
import { chromium } from 'playwright';
import zlib from 'node:zlib';

// the harness (run-e2e.sh) points these at the REAL stack — security, the mail chain, the
// broker, the saga's process manager and every content service, all of them actually running.
// An end-to-end that stubs a member proves nothing about the member it stubbed.
export const SECURITY = process.env.SECURITY_URL ?? 'http://localhost:8080';
export const MEMES = process.env.MEMES_URL ?? 'http://localhost:8083';
export const COMMENTS = process.env.COMMENTS_URL ?? 'http://localhost:8085';
export const UI = process.env.UI_URL ?? 'http://localhost:8083';
export const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';

// the saga steps wait for a chain of services to finish talking; the per-step budget has to be
// bigger than the retry window inside `eventually`, or cucumber kills the step mid-wait
setDefaultTimeout(40_000);

let browser;
let counter = 0;

/**
 * A 1x1 PNG with a UNIQUE pixel colour per call. The meme service deduplicates identical bytes
 * (MemeContentIndex), so a shared fixture PNG would silently collapse every scenario's "upload"
 * into the first one — with the first uploader as author. Chunk CRCs are real (zlib.crc32).
 */
export function uniquePng() {
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const out = Buffer.alloc(8 + data.length + 4);
    out.writeUInt32BE(data.length, 0);
    body.copy(out, 4);
    out.writeUInt32BE(zlib.crc32(body), 8 + data.length);
    return out;
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(1, 0);   // width
  ihdr.writeUInt32BE(1, 4);   // height
  ihdr[8] = 8;                // bit depth
  ihdr[9] = 6;                // RGBA
  const pixel = Buffer.from([0, ...Buffer.from([counter++ & 255, Math.floor(Math.random() * 256),
      Math.floor(Math.random() * 256), 255])]);   // filter byte + unique RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(pixel)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

BeforeAll(async () => {
  browser = await chromium.launch();
});

AfterAll(async () => {
  await browser?.close();
});

/**
 * One browser context per scenario (fresh localStorage — the gallery keeps its token there) and
 * API helpers that provision the world server-side: accounts through security's test-env mailbox,
 * memes through the real upload endpoint. The UI is only driven for what the scenario is ABOUT.
 */
class GalleryWorld {
  /** A scenario-unique account, registered and verified through the service (not the UI).
   *  `verify: false` leaves it standing at the door with the mail unread — the state a
   *  scenario needs when the POINT is what an unverified account is told. */
  async provisionAccount({ verify = true } = {}) {
    const email = `gallery-${Date.now()}-${counter++}@example.com`;
    const password = 'StrongPassword1!';
    // Both calls are CHECKED. They used to be fire-and-forget, and a refusal here does not fail
    // the step that made it — it fails three steps later, as "no token and no ticket", naming a
    // symptom instead of the refusal that caused it. A scenario that cannot provision its own
    // account has nothing left to be about, so it says so where it happened.
    await this.expectOk(this.post(`${SECURITY}/register`, { email, password }), `register ${email}`);
    this.account = { email, password };
    if (verify) {
      await this.expectOk(
        this.post(`${SECURITY}/verify-email`, { token: await this.verificationToken(email) }),
        `verify ${email}`);
    }
    return this.account;
  }

  /** The newest mail delivered to an address, read from the stack's own inbox (Mailpit) — the
   *  same way a person would read it. No test backdoor anywhere in these scenarios: the mail
   *  travelled security -> outbox -> Kafka -> microservice-email -> SMTP to get here, and if any
   *  of that were broken the scenario would fail, which is the entire point. */
  async newestMail(email, { matching, consume = false } = {}) {
    const query = encodeURIComponent(`to:${email}`);
    for (let i = 0; i < 160; i++) {
      const found = await fetch(`${MAILPIT}/api/v1/search?query=${query}&limit=25`);
      if (found.ok) {
        const { messages = [] } = await found.json();
        const newestFirst = [...messages].sort(
          (a, b) => new Date(b.Created).getTime() - new Date(a.Created).getTime());
        for (const message of newestFirst) {
          if (this.readMailIds.has(message.ID)) continue;   // a code is good for exactly one read
          const body = await (await fetch(`${MAILPIT}/api/v1/message/${message.ID}`)).json();
          const text = `${body.Text ?? ''}\n${body.HTML ?? ''}`;
          if (matching && !matching.test(text)) continue;
          if (consume) this.readMailIds.add(message.ID);
          return text;
        }
      }
      await new Promise((done) => setTimeout(done, 250));
    }
    throw new Error(`no unread matching mail for ${email}`);
  }

  /** The token in the verification link the mail carried. */
  async verificationToken(email) {
    const text = await this.newestMail(email, { matching: /[?&]verify=|token=/ });
    return decodeURIComponent(text.match(/(?:[?&]verify=|token=)([A-Za-z0-9._~%-]+)/)[1]);
  }

  /** The sign-in code (the second factor, and the step-up's own step) out of its own mail.
   *  Codes are asked for repeatedly in a scenario, so only mail newer than the request counts. */
  async signInCode(email) {
    // codes are one-shot on both sides: the newest UNREAD code mail, and never that one again —
    // a scenario that signs in twice must not re-type the first code
    const text = await this.newestMail(email, { matching: /sign-in code is/i, consume: true });
    return text.match(/sign-in code is:\s*([A-Za-z0-9-]+)/i)[1];
  }

  markCodeRequested() { /* the mailbox is read by consumption now; kept for step readability */ }

  /** The id of the meme THIS account uploaded — asked of the service, not guessed from the wall
   *  (a shared stack's gallery is full of other scenarios' memes, and "the first tile" is a lie). */
  async captureOwnMeme() {
    // /meta hands out no addresses any more, so "which one is mine?" is a question only a TOKEN
    // can ask — taken ONCE, outside the retry: signing in per attempt would burn mailed codes
    const token = await this.apiToken();
    return this.eventually(async () => {
      // the page is named, not defaulted: the listing is paged server-side, and this search only
      // works because the wall is newest-first and the upload just happened — page 0 is its page
      const listing = await (await fetch(`${MEMES}/memes?page=0&size=50`)).json();
      const ids = (Array.isArray(listing) ? listing : listing.memes ?? []).map((m) => m.id);
      for (const id of ids.slice(0, 40)) {
        const metaResponse = await fetch(`${MEMES}/memes/${id}/meta`,
          { headers: { Authorization: `Bearer ${token}` } });
        if (!metaResponse.ok) continue;          // purged under us by a neighbouring scenario
        const meta = await metaResponse.json();
        if (meta.own) {
          this.uploadedMemeId = id;
          return id;
        }
      }
      throw new Error(`no meme of ${this.account.email} on the wall yet`);
    }, { timeoutMs: 15_000 });
  }

  /** Gives the account a mailed-code second factor, over the API: the scenarios that USE the
   *  factor are about the panel's code step, not about the enrolment screen. */
  async enrolCodeFactor() {
    const token = await this.apiToken();
    const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
    await this.stepUpToEnrol(auth);
    this.markCodeRequested();
    const start = await fetch(`${SECURITY}/account/factors/EMAIL_CODE/enroll/start`,
      { method: 'POST', headers: auth, body: '{}' });
    if (!start.ok) throw new Error(`factor enrolment refused: ${start.status}`);
    const code = await this.signInCode(this.account.email);
    const confirm = await fetch(`${SECURITY}/account/factors/EMAIL_CODE/enroll/confirm`,
      { method: 'POST', headers: auth, body: JSON.stringify({ code }) });
    if (!confirm.ok) throw new Error(`factor confirmation refused: ${confirm.status}`);
  }

  /**
   * Enrolling a factor rewrites how the account signs in, so security demands a fresh step-up
   * first (P18 poz. 2) — a merely-live session, which is all a thief needs, must not be able to
   * add a factor the thief holds. The seeding path proves it the way a person would: with the
   * password, since an account without factors has nothing else to prove with.
   *
   * <p>The ACTION is a parameter because the elevation is keyed by token AND action: minting
   * recovery codes sits behind the same door under its own name, and an elevation bought for
   * 'enrol-factor' does not open it.
   */
  async stepUpToEnrol(auth, action = 'enrol-factor') {
    // mark BEFORE the request: a factored account gets a code mailed by this very call, and
    // reading the mailbox without the marker races it and finds the previous one
    this.markCodeRequested();
    const r = await fetch(`${SECURITY}/account/step-up`, {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({ action, password: this.account.password }),
    });
    if (!r.ok) throw new Error(`step-up for ${action} refused: ${r.status}`);
    const body = await r.json();
    if (body.status === 'ELEVATED') return;
    // an account that already carries a factor finishes the chain with the mailed code
    const code = await this.signInCode(this.account.email);
    const done = await fetch(`${SECURITY}/account/step-up/factor`, {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({ stepUpTicket: body.stepUpTicket, proof: code }),
    });
    if (!done.ok) throw new Error(`step-up factor refused: ${done.status}`);
  }

  /** The one-time codes shown exactly once when generated — kept for the scenario that spends one. */
  async generateRecoveryCodes() {
    const token = await this.apiToken();
    const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
    // spare keys that bypass the whole factor chain: security demands a fresh elevation for them
    // under their own action name, exactly as it does for enrolment
    await this.stepUpToEnrol(auth, 'generate-recovery-codes');
    const r = await fetch(`${SECURITY}/account/recovery-codes`, {
      method: 'POST',
      headers: auth,
    });
    if (!r.ok) throw new Error(`recovery codes refused: ${r.status}`);
    return (await r.json()).codes;
  }

  /** Fills the panel and presses Sign in — no assertion, because half the scenarios are about
   *  what happens when it does NOT simply work. */
  async signIn({ email = this.account.email, password = this.account.password } = {}) {
    this.markCodeRequested();     // a factored account gets its code mailed by this very click
    await this.page.getByLabel('e-mail').fill(email);
    await this.page.getByLabel('password', { exact: true }).fill(password);
    await this.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  }

  /** The panel's second step: the mailed code — or a recovery code, which the chain accepts
   *  in its place. */
  async submitSignInCode(code) {
    await this.page.getByLabel('sign-in code').fill(code);
    await this.page.getByRole('button', { name: 'Sign in', exact: true }).click();
  }

  /** Signs the account in over HTTP and returns the access token (for API-side seeding).
   *  An account that already carries a factor answers 202 with a ticket — the seeding path
   *  finishes that chain with the mailed code, exactly as the panel would. */
  async apiToken() {
    this.markCodeRequested();
    const r = await this.post(`${SECURITY}/authenticate`, this.account);
    const body = await r.json().catch(() => ({}));
    if (body.accessToken) return body.accessToken;
    if (!body.mfaTicket) {
      // the status and the body, not just the absence: 403 is an unverified address, 429 is a
      // throttle this suite tripped on itself, 401 is a password that does not match — three
      // different bugs that all used to arrive as one sentence naming none of them
      throw new Error(`no token and no ticket for ${this.account.email}: `
        + `${r.status} ${JSON.stringify(body)}`);
    }
    const code = await this.signInCode(this.account.email);
    const done = await this.post(`${SECURITY}/authenticate/factor`,
      { mfaTicket: body.mfaTicket, proof: code });
    return (await done.json()).accessToken;
  }

  /** Uploads a 1x1 PNG through the real endpoint; remembers the meme id. */
  async seedMeme() {
    const uploader = await this.provisionAccount();   // its own throwaway author
    const token = await this.apiToken();
    const form = new FormData();
    form.append('file', new Blob([uniquePng()], { type: 'image/png' }), 'pixel.png');
    const r = await fetch(`${MEMES}/memes`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    if (r.status !== 201) throw new Error(`seed upload failed: ${r.status}`);
    this.memeId = (await r.json()).id;
    this.account = undefined;                          // the scenario signs in as its OWN account
    return this.memeId;
  }

  /** Retries an assertion while the saga travels: security announces, offboarding orders, the
   *  content services purge and confirm. Seconds, not milliseconds — and a real deadline, so a
   *  chain that never completes fails the scenario instead of hanging it. */
  async eventually(check, { timeoutMs = 20_000, everyMs = 250 } = {}) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      try {
        return await check();
      } catch (last) {
        if (Date.now() > deadline) throw last;
        await new Promise((done) => setTimeout(done, everyMs));
      }
    }
  }

  /** The id behind the newest tile on the wall — what the scenario just uploaded, so the saga
   *  can be checked against the SERVICE and not only against the page. */
  async captureNewestMeme() {
    const src = await this.page.locator('img[src*="/thumbnail"]').first().getAttribute('src');
    this.uploadedMemeId = src.match(/memes\/([^/]+)\/thumbnail/)[1];
    return this.uploadedMemeId;
  }

  async openGallery() {
    this.context = await browser.newContext();
    this.page = await this.context.newPage();
    // kept for the After hook: when a scenario fails, what the BROWSER saw is the half of the
    // evidence the compose logs cannot give, and CI has no screen to look at
    this.browserLog = [];
    this.page.on('console', (m) => {
      if (m.type() === 'error' || m.type() === 'warning') this.browserLog.push(`${m.type()}: ${m.text()}`);
    });
    this.page.on('pageerror', (e) => this.browserLog.push(`pageerror: ${e.message}`));
    this.page.on('requestfailed', (r) =>
      this.browserLog.push(`requestfailed: ${r.method()} ${r.url()} — ${r.failure()?.errorText}`));
    await this.page.goto(UI);
  }

  /** A provisioning call that must have worked: the status and the body travel into the error,
   *  because a seeding step that fails silently makes the NEXT step tell a lie about why. */
  async expectOk(pending, what) {
    const r = await pending;
    if (!r.ok) {
      const text = await r.text().catch(() => '');
      throw new Error(`${what} refused: ${r.status} ${text}`);
    }
    return r;
  }

  post(url, body) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  }
}

setWorldConstructor(GalleryWorld);

Before(function () {
  this.memeId = undefined;
  this.uploadedMemeId = undefined;
  this.account = undefined;
  this.readMailIds = new Set();   // codes are one-shot; a scenario never re-reads its own mail
});

/**
 * On failure, say what the browser saw before throwing the context away.
 *
 * <p>Written after 2026-07-29, when seventeen scenarios failed in CI with "element(s) not found"
 * and the run was undiagnosable from the outside: the same suite passed locally against the same
 * stack, and the only artefact was an empty compose log. A failing assertion names the locator it
 * wanted; it never says what the page actually contained, which request failed, or what the console
 * said - and on CI there is no screen to look at. This prints all three, to stdout, so the evidence
 * lands in the job log whether or not an artefact upload works.
 */
After(async function ({ result }) {
  if (result?.status === 'FAILED' && this.page && !this.page.isClosed()) {
    try {
      const url = this.page.url();
      const text = (await this.page.locator('body').innerText()).replace(/\s+\n/g, '\n').trim();
      console.log(`\n--- what the browser saw at ${url} ---`);
      console.log(text.slice(0, 1500) || '(the page rendered nothing)');
      if (this.browserLog?.length) {
        console.log('--- console / network ---');
        console.log(this.browserLog.slice(0, 20).join('\n'));
      }
      console.log('--- end ---\n');
    } catch (dumpFailed) {
      console.log(`(could not read the page: ${dumpFailed.message})`);
    }
  }
  await this.context?.close();
});
