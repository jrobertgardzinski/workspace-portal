import { After, Given, When, Then } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SECURITY, MEMES, COLLECTIONS, MAILPIT, boundedFetch } from '../support/world.mjs';

// ---------------------------------------------------------------------------------------------
// The outage scenario (@outage) really stops a compose container, so these steps shell out to
// docker compose in the workspace root. It never runs in the default suite (cucumber.mjs tags
// it out) — only ../e2e-saga-outage.sh stages it, and the After hook below ALWAYS brings the
// favourites service back, green run or red.
// ---------------------------------------------------------------------------------------------

const run = promisify(execFile);
const WORKSPACE = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

function compose(...args) {
  return run('docker', ['compose', ...args], { cwd: WORKSPACE, timeout: 90_000 });
}

/**
 * GUARD for every step that manipulates real infrastructure: only the outage runner
 * (../e2e-saga-outage.sh) stages these steps, and it marks the run with E2E_OUTAGE_PROFILE=1.
 * Without the marker the step refuses loudly BEFORE touching docker — so a feature that reuses
 * "the favourites service is down" WITHOUT the @outage tag fails with this message instead of
 * silently stopping a container in the middle of the default suite.
 */
function assertOutageRun(stepName) {
  if (process.env.E2E_OUTAGE_PROFILE !== '1') {
    throw new Error(
      `"${stepName}" stops/starts real compose containers and may only run under `
      + '../e2e-saga-outage.sh (which exports E2E_OUTAGE_PROFILE=1). Running in the default '
      + 'suite? Then a feature is using this step without the @outage tag.');
  }
}

/** Subjects of the two verdict mails security can send about a deletion. */
const VERDICTS = [/your account is deleted/i, /could not delete your account/i];

/** Every mail delivered to the address whose subject or body reads like a deletion verdict. */
async function verdictMails(email) {
  const query = encodeURIComponent(`to:${email}`);
  const found = await boundedFetch(`${MAILPIT}/api/v1/search?query=${query}&limit=50`);
  if (!found.ok) throw new Error(`mailpit search failed: ${found.status}`);
  const { messages = [] } = await found.json();
  return messages.filter((m) => VERDICTS.some((verdict) => verdict.test(m.Subject ?? '')));
}

// ---------------------------------------------------------------------------------------------
// Given — a whole little life to lose: an account, a meme, a favourite.
// ---------------------------------------------------------------------------------------------

Given('a signed-in user with a meme and a favourite', async function () {
  this.leaver = await this.provisionAccount();
  this.leaverToken = await this.signIn(this.leaver);
  this.ownMemeId = await this.uploadMeme(this.leaverToken);
  await this.saveFavourite(this.leaverToken, this.ownMemeId);
});

Given('the favourites service is down', { timeout: 120_000 }, async function () {
  assertOutageRun('the favourites service is down');   // refuse before touching any container
  this.collectionsStopped = true;   // the After hook restores it unconditionally
  await compose('stop', 'user-collections');
  // prove the outage is real: the health endpoint must stop answering
  await this.eventually(async () => {
    await assert.rejects(boundedFetch(`${COLLECTIONS}/health`),
      'user-collections still answers after docker compose stop');
  }, { timeoutMs: 30_000, everyMs: 2_000 });
});

// ---------------------------------------------------------------------------------------------
// When — the goodbye, and the long silence after it. ("the user asks for their account to be
// deleted" is the same step the happy-path feature uses — account-deletion.steps.mjs owns it.)
// ---------------------------------------------------------------------------------------------

/**
 * The capitulation, watched through the MAILBOX and not the sign-in door: the apology mail is
 * committed in the same transaction that unlocks the account, so its arrival IS the verdict —
 * and polling Mailpit costs nothing, while polling sign-in during limbo reads as a wrong
 * password and three of those from one address trip the brute-force block.
 *
 * The deadline covers the arithmetic with margin: capitulation at ~172s after the request
 * (120s purge timeout from the saga's start + 3 sweeps of re-commands + the give-up sweep,
 * 15s apart), plus the outbox -> Kafka -> email -> SMTP road for the mail itself.
 */
When('the portal gives up waiting for the favourites service', { timeout: 300_000 },
  async function () {
    this.apologyMail = await this.eventually(
      () => this.newestMail(this.leaver.email, { matching: /could not delete your account/i }),
      { timeoutMs: 240_000, everyMs: 2_000 });
  });

// ---------------------------------------------------------------------------------------------
// Then — limbo first, then the honest hand-back.
// ---------------------------------------------------------------------------------------------

Then('the account hangs in deletion limbo: signing in is refused and no verdict mail has arrived',
  { timeout: 120_000 },
  async function () {
    // exactly TWO sign-in samples across the window, not a poll: each refusal of an account in
    // limbo counts as a failed attempt, and the third would arm the brute-force block that the
    // unlocked account must sail through later
    const refused = await this.post(`${SECURITY}/authenticate`, this.leaver);
    assert.equal(refused.status, 401, `limbo sign-in answered ${refused.status}`);
    assert.equal((await verdictMails(this.leaver.email)).length, 0,
      'a verdict mail arrived although the saga cannot have finished');
    await new Promise((done) => setTimeout(done, 30_000));   // the saga is still re-asking
    const stillRefused = await this.post(`${SECURITY}/authenticate`, this.leaver);
    assert.equal(stillRefused.status, 401,
      `sign-in answered ${stillRefused.status} after 30s of limbo`);
    assert.equal((await verdictMails(this.leaver.email)).length, 0,
      'a verdict mail arrived although the favourites service is still down');
  });

Then('their meme has already disappeared from the gallery', async function () {
  // the memes participant is up and purges on the first command — the partial purge is
  // visible while the account itself still hangs
  await this.eventually(async () => {
    const direct = await boundedFetch(`${MEMES}/memes/${this.ownMemeId}`);
    assert.equal(direct.status, 404, `the meme still answers ${direct.status}`);
    // and gone from the WALL, which is what "from the gallery" says: the listing is paged, so the
    // page is named (world.wallIds) — the same question account-deletion's step asks
    const wall = await this.wallIds();
    assert.ok(!wall.includes(this.ownMemeId), 'the meme still hangs on the public wall');
  });
});

Then('the account is handed back: signing in works again', async function () {
  // the unlock committed before the apology mail could even leave, so the first attempt
  // should already pass — and a successful sign-in wipes this scenario's failed attempts
  await this.eventually(async () => {
    this.leaverToken = await this.signIn(this.leaver);
  }, { timeoutMs: 20_000, everyMs: 5_000 });
});

Then('a mail apologises that the deletion could not be completed', function () {
  assert.ok(this.apologyMail, 'no apology mail was captured');
  assert.match(this.apologyMail, /could not delete your account/i);
});

Then('their meme stays gone', async function () {
  // capitulation gives the ACCOUNT back, not the purged content: memes confirmed its purge
  // long ago and the failure outcome names that partial purge — the meme must not resurrect
  const direct = await boundedFetch(`${MEMES}/memes/${this.ownMemeId}`);
  assert.equal(direct.status, 404, `the purged meme answers ${direct.status} again`);
  const wall = await this.wallIds();
  assert.ok(!wall.includes(this.ownMemeId), 'the purged meme is back on the public wall');
});

// ---------------------------------------------------------------------------------------------
// After — the stack is repaired NO MATTER WHAT, then the leftover account is folded up.
// Defined after support/world.mjs's After, so cucumber runs this one FIRST (LIFO): the
// favourites service is back before any farewell needs the saga to actually complete.
// ---------------------------------------------------------------------------------------------

After({ tags: '@outage', timeout: 180_000 }, async function () {
  if (this.collectionsStopped) {
    await compose('start', 'user-collections');
    await this.eventually(async () => {
      const health = await boundedFetch(`${COLLECTIONS}/health`);
      assert.ok(health.ok, `user-collections /health answers ${health.status}`);
    }, { timeoutMs: 90_000, everyMs: 3_000 });
    this.collectionsStopped = false;
  }
  // best-effort hygiene: after a PASSED capitulation the leaver still exists — ask for the
  // deletion again on the repaired stack (world.mjs's After only folds the leaver up on
  // failure). Fresh sign-in, because the old token may be stale by now.
  try {
    const token = await this.signIn(this.leaver);
    await this.requestAccountDeletion(this.leaver, token);
  } catch {
    // already gone, still pending, or the stack is having a day — cleanup stays silent
  }
});
