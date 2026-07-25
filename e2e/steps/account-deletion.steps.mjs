import { Given, When, Then } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { SECURITY, MEMES } from '../support/world.mjs';

// ---------------------------------------------------------------------------------------------
// Given — the world before the goodbye. Everything is provisioned through the public endpoints:
// the bystander (whose meme must survive) and the leaver both register, read their verification
// mail in Mailpit and sign in, exactly as a person would.
// ---------------------------------------------------------------------------------------------

/** A bystander's meme for the leaver to comment on / save. It belongs to someone ELSE on
 *  purpose: the leaver's own meme is purged with them, and a comment under it would vanish by
 *  thread-cascade — proving nothing about the comment policy itself. The account and token are
 *  kept on the world so the After hook can fold the bystander up again once the scenario ends. */
async function someoneElsesMeme(world) {
  world.bystander = await world.provisionAccount();
  world.bystanderToken = await world.signIn(world.bystander);
  world.othersMemeId = await world.uploadMeme(world.bystanderToken);
}

Given('a signed-in user with a meme, a comment and a favourite', async function () {
  await someoneElsesMeme(this);
  this.leaver = await this.provisionAccount();
  this.leaverToken = await this.signIn(this.leaver);
  this.ownMemeId = await this.uploadMeme(this.leaverToken);
  this.commentText = `I was here — ${this.leaver.email}`;
  await this.postComment(this.leaverToken, this.othersMemeId, this.commentText);
  await this.saveFavourite(this.leaverToken, this.othersMemeId);
});

Given("a signed-in user who commented on someone else's meme", async function () {
  await someoneElsesMeme(this);
  this.leaver = await this.provisionAccount();
  this.leaverToken = await this.signIn(this.leaver);
  this.commentText = `chosen to vanish — ${this.leaver.email}`;
  await this.postComment(this.leaverToken, this.othersMemeId, this.commentText);
});

// ---------------------------------------------------------------------------------------------
// When — the single act the feature is about.
// ---------------------------------------------------------------------------------------------

When('the user asks for their account to be deleted', async function () {
  await this.requestAccountDeletion(this.leaver, this.leaverToken);
});

When('the user asks for their account to be deleted, choosing that their comments be erased',
  async function () {
    await this.requestAccountDeletion(this.leaver, this.leaverToken, { comments: 'DELETE' });
  });

// ---------------------------------------------------------------------------------------------
// Then — the promise, watched with a deadline while the saga travels the broker. Each check
// polls the PUBLIC read side (gallery, thread, favourites list, sign-in door, mailbox): what a
// person, or anyone else on the portal, would actually see.
// ---------------------------------------------------------------------------------------------

Then('their meme disappears from the gallery', async function () {
  await this.eventually(async () => {
    const direct = await fetch(`${MEMES}/memes/${this.ownMemeId}`);
    assert.equal(direct.status, 404, `the meme still answers ${direct.status}`);
    const wall = await (await fetch(`${MEMES}/memes`)).text();
    assert.ok(!wall.includes(this.ownMemeId), 'the meme still hangs on the public wall');
  });
});

Then('their comment stays readable but is signed {string}', async function (byline) {
  await this.eventually(async () => {
    const thread = await this.commentsUnder(this.othersMemeId);
    const comment = thread.find((c) => c.text === this.commentText);
    assert.ok(comment, 'the comment is gone — the default policy should have kept its text');
    assert.equal(comment.author, byline,
      `the comment is still signed "${comment.author}"`);
  });
});

Then('their comment disappears from the thread entirely', async function () {
  await this.eventually(async () => {
    const thread = await this.commentsUnder(this.othersMemeId);
    assert.ok(!thread.some((c) => c.text === this.commentText),
      'the comment still stands in the thread');
  });
});

Then('their list of favourites is empty', async function () {
  // the access token verifies offline in user-collections (JWKS), so until it expires it still
  // opens the leaver's list — which the saga must have emptied
  await this.eventually(async () => {
    const items = await this.favourites(this.leaverToken);
    assert.equal(items.length, 0, `${items.length} favourite(s) still saved`);
  });
});

Then('they can no longer sign in', async function () {
  await this.eventually(async () => {
    const r = await this.post(`${SECURITY}/authenticate`, this.leaver);
    assert.equal(r.status, 401, `sign-in still answers ${r.status}`);
  }, { timeoutMs: 15_000 });
});

Then('a farewell mail tells them the account is deleted', async function () {
  // sent in the same transaction that deletes the user row — once it shows up, the account IS
  // gone for good (the mail travelled the same outbox -> Kafka -> email -> SMTP road as always)
  await this.newestMail(this.leaver.email, { matching: /account is deleted/i });
});
