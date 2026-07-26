import { After, Before, Given, When, Then } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { MEMES, boundedFetch } from '../support/world.mjs';

/**
 * The deletion cascade, end to end. Two actors at most, both provisioned through the public
 * endpoints like everywhere else in this harness:
 *
 *   - `author` — uploads the meme and is the only one allowed to take it down
 *   - `fan`    — the person whose favourites the scenario is actually about
 *
 * In the first scenario they are the same person (the brief's "the author deletes their own
 * meme"); in the second they are not, which is what makes the comment hop worth proving: the
 * comment the fan saved is dropped by a service the fan never talked to, because the MEME was
 * deleted by somebody else entirely.
 */

Before(function () {
  this.author = undefined;
  this.authorToken = undefined;
  this.fan = undefined;          // the saver — may be the same account as the author
  this.fanToken = undefined;
  this.cascadeMemeId = undefined;
  this.savedCommentId = undefined;
  this.savedCommentText = undefined;
});

// Scenario hygiene on the shared dev stack, walked through the public API: both actors ask to be
// forgotten once the assertions are done. Best-effort — cleanup must never redden a green
// scenario, and the account-deletion saga has its own feature to be judged by.
After(async function () {
  for (const [account, token] of [[this.author, this.authorToken], [this.fan, this.fanToken]]) {
    if (!account || !token) continue;
    try {
      await this.requestAccountDeletion(account, token);
    } catch {
      // already folded up (author and fan are one account in the first scenario), token expired,
      // stack half-down — cleanup stays silent
    }
  }
});

// ---------------------------------------------------------------------------------------------
// Given — a saved reference that is about to lose the thing it points at.
// ---------------------------------------------------------------------------------------------

Given('a signed-in user who saved a meme of their own in their favourites', async function () {
  this.author = await this.provisionAccount();
  this.authorToken = await this.signIn(this.author);
  // one person wearing both hats: they may take the meme down BECAUSE they are its author, and
  // they watch their own list empty out
  this.fan = this.author;
  this.fanToken = this.authorToken;
  this.cascadeMemeId = await this.uploadMeme(this.authorToken);
  await this.saveFavourite(this.fanToken, this.cascadeMemeId);
});

Given("a signed-in user who saved someone else's comment in their favourites", async function () {
  this.author = await this.provisionAccount();
  this.authorToken = await this.signIn(this.author);
  this.cascadeMemeId = await this.uploadMeme(this.authorToken);

  this.fan = await this.provisionAccount();
  this.fanToken = await this.signIn(this.fan);
  // the comment is the AUTHOR's, under the author's own meme: the fan saved a piece of somebody
  // else's conversation, and nothing they did will cause its removal
  this.savedCommentText = `worth keeping — ${Date.now()}`;
  this.savedCommentId =
    await this.postComment(this.authorToken, this.cascadeMemeId, this.savedCommentText);
  await this.saveRef(this.fanToken, 'comment', this.savedCommentId);
});

// ---------------------------------------------------------------------------------------------
// When — the single act, performed by the one person entitled to perform it.
// ---------------------------------------------------------------------------------------------

When('the author takes that meme down', async function () {
  const answer = await this.deleteMeme(this.authorToken, this.cascadeMemeId);
  assert.equal(answer.status, 'DELETED', 'the gallery did not accept the deletion');
  assert.equal(answer.by, 'AUTHOR', 'the deletion must be the author\'s own doing');
});

// ---------------------------------------------------------------------------------------------
// Then — the promise, watched with a deadline. The gallery's part is immediate (the delete is
// synchronous); everything after it travels the broker, so it is polled.
// ---------------------------------------------------------------------------------------------

Then('the meme is gone from the gallery', async function () {
  const direct = await boundedFetch(`${MEMES}/memes/${this.cascadeMemeId}`);
  assert.equal(direct.status, 404, `the meme still answers ${direct.status}`);
  // "from the gallery" is the WALL too, not only the direct fetch: the listing is paged, so the
  // page is named (world.wallIds) — the same question the other two features ask
  const wall = await this.wallIds();
  assert.ok(!wall.includes(this.cascadeMemeId), 'the meme still hangs on the public wall');
});

Then('the comment is gone from the thread', async function () {
  // the first hop: comments drops the whole thread when the meme dies. Polled — it rides the
  // broker like everything else downstream of the delete
  await this.eventually(async () => {
    const thread = await this.commentsUnder(this.cascadeMemeId);
    assert.ok(!thread.some((c) => c.id === this.savedCommentId),
      'the comment still stands under a meme that no longer exists');
  });
});

Then('it eventually disappears from my favourites', async function () {
  await cascadeDropsRef(this, 'meme', this.cascadeMemeId);
});

Then('the saved comment eventually disappears from my favourites', async function () {
  // the SECOND hop, and the one worth an end-to-end: memes announced the deletion, comments
  // answered with the ids it dropped, and user-collections removed a reference nobody ever told
  // it about directly — the fan saved a comment id, not a meme id
  await cascadeDropsRef(this, 'comment', this.savedCommentId);
});

Then('my favourites end up empty', async function () {
  await this.eventually(async () => {
    const items = await this.favourites(this.fanToken);
    assert.deepEqual(items, [], `${items.length} favourite(s) still saved`);
  });
});

/** The polled shape both cascade legs share: THIS reference must leave the list. */
function cascadeDropsRef(world, itemType, itemId) {
  return world.eventually(async () => {
    const items = await world.favourites(world.fanToken);
    assert.ok(!items.some((ref) => ref.itemType === itemType && ref.itemId === itemId),
      `the ${itemType} reference is still in the favourites list`);
  });
}
