import { After, Before, Status, setWorldConstructor, setDefaultTimeout } from '@cucumber/cucumber';
import zlib from 'node:zlib';

// e2e-saga.sh points these at the LIVE stack — security, the mail chain, the broker, the saga's
// process manager and every content service, all of them actually running. An end-to-end that
// stubs a member proves nothing about the member it stubbed.
export const SECURITY = process.env.SECURITY_URL ?? 'http://localhost:8080';
export const MEMES = process.env.MEMES_URL ?? 'http://localhost:8083';
export const COMMENTS = process.env.COMMENTS_URL ?? 'http://localhost:8085';
export const COLLECTIONS = process.env.COLLECTIONS_URL ?? 'http://localhost:8092';
export const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';

// the saga's Then-steps wait for a chain of services to finish talking (deadline ~60 s inside
// `eventually`); the per-step budget has to be bigger than that window, or cucumber kills the
// step mid-wait
setDefaultTimeout(90_000);

/**
 * Every wire call goes through here: fetch with a hard 10 s cap. A hanging service must fail the
 * call (AbortError) instead of pinning it — `eventually` then retries, and the After-hook's
 * best-effort farewell stays best-effort instead of eating the whole cucumber step budget and
 * failing a green scenario on a timeout that was never the scenario's fault.
 *
 * A caller's own `options.signal` is respected, not overwritten: both signals abort the call,
 * whichever fires first.
 */
export function boundedFetch(url, options = {}) {
  const cap = AbortSignal.timeout(10_000);
  const signal = options.signal ? AbortSignal.any([options.signal, cap]) : cap;
  return fetch(url, { ...options, signal });
}

let counter = 0;

/**
 * A 1x1 PNG with a UNIQUE pixel colour per call. The meme service deduplicates identical bytes
 * (MemeContentIndex), so a shared fixture PNG would silently collapse every scenario's "upload"
 * into the first one — with the first uploader as author. Chunk CRCs are real (zlib.crc32).
 * (Same trick as memes-ui/e2e — kept in the harness's own style.)
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
  const pixel = Buffer.from([0, counter++ & 255, Math.floor(Math.random() * 256),
      Math.floor(Math.random() * 256), 255]);   // filter byte + unique RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(pixel)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/**
 * The saga's world, all of it over plain HTTP: accounts are registered and verified the way a
 * person would do it (the verification mail is read from the stack's own inbox, Mailpit — the
 * mail travelled security -> outbox -> Kafka -> microservice-email -> SMTP to get there), and
 * content is created through the same public endpoints the UIs call. No test backdoors.
 */
class SagaWorld {

  /** A run-unique account, registered and verified through the services. */
  async provisionAccount() {
    const email = `saga-${Date.now()}-${counter++}@example.com`;
    const password = 'StrongPassword1!';
    const registered = await this.post(`${SECURITY}/register`, { email, password });
    if (registered.status !== 201) throw new Error(`register refused: ${registered.status}`);
    const token = await this.verificationToken(email);
    const verified = await this.post(`${SECURITY}/verify-email`, { token });
    if (!verified.ok) throw new Error(`verify-email refused: ${verified.status}`);
    return { email, password };
  }

  /** The newest mail delivered to an address, read from Mailpit like a person would. */
  async newestMail(email, { matching } = {}) {
    const query = encodeURIComponent(`to:${email}`);
    for (let i = 0; i < 240; i++) {
      const found = await boundedFetch(`${MAILPIT}/api/v1/search?query=${query}&limit=25`);
      if (found.ok) {
        const { messages = [] } = await found.json();
        const newestFirst = [...messages].sort(
          (a, b) => new Date(b.Created).getTime() - new Date(a.Created).getTime());
        for (const message of newestFirst) {
          const body = await (await boundedFetch(`${MAILPIT}/api/v1/message/${message.ID}`)).json();
          const text = `${body.Text ?? ''}\n${body.HTML ?? ''}`;
          if (matching && !matching.test(text)) continue;
          return text;
        }
      }
      await new Promise((done) => setTimeout(done, 250));
    }
    throw new Error(`no matching mail for ${email}`);
  }

  /** The token in the verification link the mail carried. */
  async verificationToken(email) {
    const text = await this.newestMail(email, { matching: /[?&]verify=|token=/ });
    return decodeURIComponent(text.match(/(?:[?&]verify=|token=)([A-Za-z0-9._~%-]+)/)[1]);
  }

  /** Signs an account in over HTTP and returns the access token. */
  async signIn(account) {
    const r = await this.post(`${SECURITY}/authenticate`, account);
    if (!r.ok) throw new Error(`sign-in refused: ${r.status}`);
    const { accessToken } = await r.json();
    if (!accessToken) throw new Error('sign-in answered without a token');
    return accessToken;
  }

  /** Uploads a unique 1x1 PNG through the real endpoint; answers the meme id. */
  async uploadMeme(token) {
    const form = new FormData();
    form.append('file', new Blob([uniquePng()], { type: 'image/png' }), 'pixel.png');
    const r = await boundedFetch(`${MEMES}/memes`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    });
    if (r.status !== 201) throw new Error(`meme upload failed: ${r.status}`);
    return (await r.json()).id;
  }

  /** Posts a comment under a meme; answers the comment id. */
  async postComment(token, memeId, text) {
    const r = await boundedFetch(`${COMMENTS}/memes/${memeId}/comments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ text }),
    });
    if (r.status !== 201) throw new Error(`comment refused: ${r.status}`);
    return (await r.json()).id;
  }

  /**
   * The ids on one page of the PUBLIC wall.
   *
   * The gallery listing is PAGED and caps its size server-side, so "the wall" is never a request
   * for everything — and a scenario that asks for the default page is asking a question it cannot
   * state. Every step that reads absence out of the listing goes through here: the page is named
   * explicitly, because an absence proves nothing if nobody can say what was searched. Page 0 with
   * a full-size page is the honest default for these scenarios — the listing is newest-first and
   * the memes involved were uploaded minutes ago, so page 0 is the page they would be on.
   */
  async wallIds({ page = 0, size = 100 } = {}) {
    const r = await boundedFetch(`${MEMES}/memes?page=${page}&size=${size}`);
    if (!r.ok) throw new Error(`gallery listing failed: ${r.status}`);
    return (await r.json()).map((row) => row.id);
  }

  /** The public comment thread under a meme. */
  async commentsUnder(memeId) {
    const r = await boundedFetch(`${COMMENTS}/memes/${memeId}/comments`);
    if (!r.ok) throw new Error(`comments listing failed: ${r.status}`);
    return r.json();
  }

  /** Saves a meme into the account's favourites and proves the save took. */
  saveFavourite(token, memeId) {
    return this.saveRef(token, 'meme', memeId);
  }

  /**
   * Saves ANY reference into the account's favourites and proves the save took. The collection is
   * a bag of opaque `(itemType, itemId)` pointers — a comment is as savable as a meme, which is
   * what the second hop of the deletion cascade is about — so the type is the caller's word.
   * Verified by finding THIS ref rather than by counting: a scenario may save more than one.
   */
  async saveRef(token, itemType, itemId) {
    const put = await boundedFetch(
      `${COLLECTIONS}/collections/favourites/items/${itemType}/${itemId}`, {
        method: 'PUT',
        headers: { Authorization: `Bearer ${token}` },
      });
    if (!put.ok) throw new Error(`favourite save refused: ${put.status}`);
    const items = await this.favourites(token);
    if (!items.some((ref) => ref.itemType === itemType && ref.itemId === itemId)) {
      throw new Error(`the saved ${itemType} did not appear in the list`);
    }
  }

  /** Takes a meme down as its author would, through the gallery's own endpoint. */
  async deleteMeme(token, memeId) {
    const r = await boundedFetch(`${MEMES}/memes/${memeId}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) throw new Error(`meme deletion refused: ${r.status}`);
    return r.json();
  }

  /** The account's saved favourites. The access token verifies OFFLINE in user-collections
   *  (JWKS, not introspection), so it still opens the — by then empty — list after deletion. */
  async favourites(token) {
    const r = await boundedFetch(`${COLLECTIONS}/collections/favourites/items`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!r.ok) throw new Error(`favourites listing failed: ${r.status}`);
    return r.json();
  }

  /** The RODO exit as the user walks it: prove it is really them (step-up — a stolen session
   *  must not end an account), then ask for the deletion; the answer is a promise (202), the
   *  keeping of which the Then-steps watch. `purge` carries the wizard's explicit choice; no
   *  purge means every content service applies its deployment default. */
  async requestAccountDeletion(account, token, purge = undefined) {
    const elevated = await boundedFetch(`${SECURITY}/account/step-up`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ action: 'delete-account', password: account.password }),
    });
    if (!elevated.ok) throw new Error(`step-up before deletion refused: ${elevated.status}`);
    const r = await boundedFetch(`${SECURITY}/account/delete`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: purge ? JSON.stringify({ purge }) : undefined,
    });
    if (r.status !== 202) throw new Error(`deletion request expected 202, got ${r.status}`);
  }

  /** Retries an assertion while the saga travels: security announces, offboarding orders, the
   *  content services purge and confirm. A real deadline, so a chain that never completes fails
   *  the scenario instead of hanging it. */
  async eventually(check, { timeoutMs = 60_000, everyMs = 1_000 } = {}) {
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

  post(url, body) {
    return boundedFetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  }
}

setWorldConstructor(SagaWorld);

Before(function () {
  this.leaver = undefined;         // the account that asks to be forgotten
  this.leaverToken = undefined;    // kept: it verifies offline in user-collections until exp
  this.bystander = undefined;      // the other account — provisioned per scenario, cleaned after
  this.bystanderToken = undefined;
  this.ownMemeId = undefined;      // the leaver's upload — must vanish
  this.othersMemeId = undefined;   // a bystander's meme — the comment/favourite target, must stay
  this.commentText = undefined;    // run-unique, so the thread pins THIS scenario's comment
});

// Scenario hygiene on the shared dev stack, walked through the very API under test: the
// bystander asks for their own deletion too (their meme goes with them — the scenario's
// assertions are long done by now), and a failed scenario also tries to fold up the leaver the
// passing path would have deleted. Best-effort on purpose: cleanup must never turn a green
// scenario red or bury a real failure under its own.
After(async function ({ result }) {
  const farewell = async (account, token) => {
    if (!account || !token) return;
    try {
      await this.requestAccountDeletion(account, token);
    } catch {
      // already deleted, stack half-down, token expired — cleanup stays silent
    }
  };
  await farewell(this.bystander, this.bystanderToken);
  if (result?.status !== Status.PASSED) {
    await farewell(this.leaver, this.leaverToken);
  }
});
