import { Then, When } from '@cucumber/cucumber';
import { expect } from 'playwright/test';
import { MEMES } from '../support/world.mjs';

When('they favourite the meme', async function () {
  await this.page.getByRole('button', { name: 'favourite', exact: true }).first().click();
  await expect(this.page.getByRole('button', { name: 'unfavourite' }).first()).toBeVisible();
});

When('they favourite their own meme', async function () {
  // the wall holds other scenarios' memes too — star exactly the one this account uploaded
  // (poll: the UI's upload fires and forgets, the server may still be writing)
  let mine;
  // /meta masks the uploader, so "is this mine?" is a question only a TOKEN can answer — the
  // server compares the identities and replies with own
  const token = await this.apiToken();
  for (let attempt = 0; attempt < 20 && !mine; attempt++) {
    const memes = await (await fetch(`${MEMES}/memes`)).json();
    for (const meme of memes) {
      // a shared wall changes under us: a meme another scenario just purged answers 404 with an
      // empty body, and json() on that is a crash, not a miss
      const metaResponse = await fetch(`${MEMES}/memes/${meme.id}/meta`,
        { headers: { Authorization: `Bearer ${token}` } });
      if (!metaResponse.ok) continue;
      const meta = await metaResponse.json();
      if (meta.own) { mine = meme.id; break; }
    }
    if (!mine) await new Promise((r) => setTimeout(r, 250));
  }
  if (!mine) throw new Error('no meme by this account on the wall yet');
  await this.page.reload();   // make sure the fresh tile is rendered before starring it
  const tile = this.page.locator('.MuiCard-root', { has: this.page.locator(`img[src*="${mine}"]`) });
  await tile.getByRole('button', { name: 'favourite', exact: true }).click();
  await expect(tile.getByRole('button', { name: 'unfavourite' })).toBeVisible();
});

When('unfavourite the meme', async function () {
  await this.page.getByRole('button', { name: 'unfavourite' }).first().click();
});

When('switch to the favourites wall', async function () {
  await this.page.getByRole('button', { name: 'Favourites' }).click();
});

Then('the favourites wall is empty', async function () {
  await expect(this.page.getByText('No favourites yet')).toBeVisible();
});

// deletion happens server-side (the author's own DELETE) and the gallery is none the wiser until
// it hydrates — but it is not the last word: MEME_DELETED then travels to user-collections, which
// drops the ref without anyone asking the browser
When("the meme is deleted behind the gallery's back", async function () {
  // services persist across scenarios, so find THIS account's meme by ownership, not position
  const memes = await (await fetch(`${MEMES}/memes`)).json();
  const token = await this.apiToken();
  for (const meme of memes) {
    // own, not the address: /meta is public and hands out no e-mails (the token makes it answer)
    const meta = await (await fetch(`${MEMES}/memes/${meme.id}/meta`,
      { headers: { Authorization: `Bearer ${token}` } })).json();
    if (meta.own) {
      const r = await fetch(`${MEMES}/memes/${meme.id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!r.ok) throw new Error(`the account's meme would not die: ${r.status}`);
      return;
    }
  }
  throw new Error('no meme by this account found to delete');
});

Then('the cascade eventually sweeps it off the favourites wall', async function () {
  // This scenario used to assert the "unavailable" keepsake. That tile is REAL and App.tsx still
  // renders it — but it only exists in the window between the author's DELETE and MEME_DELETED
  // reaching user-collections, and since the cascade landed (44e8616 in user-collections) that
  // window closes in seconds. A browser cannot reliably be looking during it, which is why the
  // scenario went permanently red instead of flaky. What IS deterministic is where the cascade
  // ends up, and portal/e2e/deletion-cascade.feature asserts the same outcome one layer down.
  // The transient tile belongs in a UI unit test against a mocked 404; memes-ui has no unit
  // suite yet, so that state is currently uncovered — see PLAN-P13.md.
  await this.eventually(async () => {
    // App.tsx:223 refetches only on the way IN (`if (!showFavourites && token)`), so the wall has
    // to be left and re-entered to ask collections again — otherwise this loop would re-read one
    // stale render for twenty seconds and then fail on it. The one button carries both labels
    // (App.tsx:228), so leaving and returning are two different names, not two clicks on one.
    await this.page.getByRole('button', { name: 'Back to the wall' }).click();
    await this.page.getByRole('button', { name: 'Favourites' }).click();
    await expect(this.page.getByText('No favourites yet')).toBeVisible({ timeout: 1_000 });
  });
});
