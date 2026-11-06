import { Given, Then, When } from '@cucumber/cucumber';
import { expect } from 'playwright/test';
import { uniquePng } from '../support/world.mjs';

Given('a meme has been uploaded by someone', async function () {
  await this.seedMeme();
});

Given('a verified account exists', async function () {
  await this.provisionAccount();
});

When('the visitor opens the gallery', async function () {
  await this.openGallery();
});

When('signs in with that account', signIn);
async function signIn() {
  await this.signIn();          // the filling lives in the world — the identity scenarios share it
  await expect(this.page.getByText(`signed in as ${this.account.email}`)).toBeVisible();
}

Then('the panel shows they are signed in', async function () {
  await expect(this.page.getByText(`signed in as ${this.account.email}`)).toBeVisible();
});

Then("the meme's tile is on the wall", async function () {
  await expect(this.page.locator('img[src*="/thumbnail"]').first()).toBeVisible();
});

When('opens the meme', openTheMeme);
async function openTheMeme() {
  await this.page.locator('img[src*="/thumbnail"]').first().click();
  await expect(this.page.getByTestId('meme-score')).toBeVisible();
}

Then('opening it shows {string}', async function (caption) {
  await this.page.locator('img[src*="/thumbnail"]').first().click();
  await expect(this.page.getByText(caption)).toBeVisible();
});

When('they upload an image', async function () {
  // unique bytes — identical content would be deduplicated into someone else's meme
  await this.page.locator('input[type="file"]').setInputFiles({
    name: 'fresh.png', mimeType: 'image/png', buffer: uniquePng(),
  });
});

When('they vote it up', async function () {
  await this.page.getByRole('button', { name: 'vote up' }).click();
});

Then('the score shows {int}', async function (score) {
  await expect(this.page.getByTestId('meme-score')).toHaveText(String(score));
});

When('they post the comment {string}', async function (text) {
  await this.page.getByPlaceholder('add a comment…').fill(text);
  await this.page.getByRole('button', { name: 'Post' }).click();
});

Then('the thread shows {string}', async function (text) {
  await expect(this.page.getByText(text)).toBeVisible();
});
