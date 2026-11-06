import { Given, Then, When } from '@cucumber/cucumber';
import { expect } from 'playwright/test';
import { COMMENTS, MEMES, SECURITY, UI } from '../support/world.mjs';

// --- the doors into the gallery ---------------------------------------------------------------

Given('an account exists that never followed its verification link', async function () {
  await this.provisionAccount({ verify: false });
});

Given('the account carries a mailed sign-in code factor', async function () {
  await this.enrolCodeFactor();
});

Given('the account has recovery codes', async function () {
  this.recoveryCodes = await this.generateRecoveryCodes();
});

When('they create an account from the panel', async function () {
  this.account = {
    email: `signup-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`,
    password: 'StrongPassword1!',
  };
  await this.page.getByRole('tab', { name: 'Create account' }).click();
  await this.page.getByLabel('e-mail').fill(this.account.email);
  await this.page.getByLabel('password', { exact: true }).fill(this.account.password);
  await this.page.getByRole('button', { name: 'Create account' }).click();
});

Then('the panel says to check the mail', async function () {
  await expect(this.page.getByText(/check the mail we sent/i)).toBeVisible();
});

Then('the panel says the address is not verified yet', async function () {
  await expect(this.page.getByText(/not verified yet/i)).toBeVisible();
});

When('they follow the link from that mail', async function () {
  // the browser cannot read e-mail: the test-environment mailbox hands over what was "sent",
  // and the visitor arrives on exactly the link the mail carries
  const token = await this.verificationToken(this.account.email);
  await this.page.goto(`${UI}/?verify=${encodeURIComponent(token)}`);
});

Then('the panel says the address is verified', async function () {
  await expect(this.page.getByText(/verified/i)).toBeVisible();
});

Then('signing in with the new account works', async function () {
  await this.signIn();
  await expect(this.page.getByText(`signed in as ${this.account.email}`)).toBeVisible();
});

When('they sign in with that account', async function () {
  await this.signIn();
});

When('they sign in with the wrong password', async function () {
  await this.signIn({ password: 'NotThePassword9!' });
});

Then('the panel refuses them', async function () {
  await expect(this.page.getByRole('alert')).toBeVisible();
});

Then('the panel does not show them as signed in', async function () {
  await expect(this.page.getByText(`signed in as ${this.account.email}`)).toHaveCount(0);
});

Then('the panel asks for the sign-in code', async function () {
  await expect(this.page.getByText(/we e-mailed a sign-in code/i)).toBeVisible();
});

When('they type the code from their mail', async function () {
  await this.submitSignInCode(await this.signInCode(this.account.email));
});

When('they type a recovery code instead', async function () {
  await this.submitSignInCode(this.recoveryCodes[0]);
});

// --- the way out --------------------------------------------------------------------------

Given('signs in with that account through the code step', async function () {
  await this.signIn();
  await expect(this.page.getByText(/we e-mailed a sign-in code/i)).toBeVisible();
  await this.submitSignInCode(await this.signInCode(this.account.email));
  await expect(this.page.getByText(`signed in as ${this.account.email}`)).toBeVisible();
});

When('they open the danger zone', async function () {
  await this.page.getByRole('button', { name: 'delete account…' }).click();
  await expect(this.page.getByRole('heading', { name: 'Delete your account' })).toBeVisible();
});

When('confirm the deletion with their password', async function () {
  this.markCodeRequested();     // the step-up mails its own code when a factor is enrolled
  await this.page.getByLabel('your password').fill(this.account.password);
  await this.page.getByRole('button', { name: 'Delete my account' }).click();
});

When('confirm the deletion with the wrong password', async function () {
  await this.page.getByLabel('your password').fill('NotThePassword9!');
  await this.page.getByRole('button', { name: 'Delete my account' }).click();
});

When('keep their account instead', async function () {
  await this.page.getByRole('button', { name: 'Keep my account' }).click();
});

Then('the dialog asks for the sign-in code', async function () {
  await expect(this.page.getByLabel('sign-in code')).toBeVisible();
});

When('they confirm the deletion with the code from their mail', async function () {
  await this.page.getByLabel('sign-in code').fill(await this.signInCode(this.account.email));
  await this.page.getByRole('button', { name: 'Confirm & delete' }).click();
});

Then('the panel says the deletion started', async function () {
  await expect(this.page.getByText(/Account deletion started/i)).toBeVisible();
});

Then('the dialog complains about the password', async function () {
  await expect(this.page.getByText('Wrong password.')).toBeVisible();
});

Then('signing in with that account is refused', async function () {
  // the saga has a road to travel (security -> offboarding -> the content services and back),
  // so the front door closes for good within a beat rather than instantly
  //
  // What "refused" has to mean, spelled out. This step used to assert only `status !== 200`, and
  // for an account WITH a second factor that can never fail: a live, healthy account with a factor
  // answers 202 with an mfaTicket (see world.mjs — the twin step "still works" accepts [200, 202]
  // as proof of LIFE for exactly that reason). Scenario 5, "A second factor is asked for on the way
  // out too", ends on this step as its only evidence that the deletion happened — so the one
  // scenario covering the MFA exit was the one scenario that could not fail here. A regression in
  // the step-up path would leave the no-factor scenarios honestly green and this one dishonestly
  // green beside them.
  //
  // So: neither door may open. No access token, and no ticket to walk through either.
  await this.eventually(async () => {
    const r = await this.post(`${SECURITY}/authenticate`, this.account);
    expect(r.status, 'a deleted account cannot come back through the front door').not.toBe(200);

    const body = await r.json().catch(() => ({}));
    expect(body.accessToken, 'a deleted account gets no access token').toBeFalsy();
    expect(body.mfaTicket,
      'and no second-factor ticket either — a 202 with a ticket is what a LIVE account with a'
      + ' factor answers, so accepting it here would pass for an account that was never deleted')
      .toBeFalsy();
  });
});

When('close the meme', async function () {
  await this.page.keyboard.press('Escape');
  await expect(this.page.getByTestId('meme-score')).toHaveCount(0);
});

Then('their meme is on the wall', async function () {
  await expect(this.page.locator('img[src*="/thumbnail"]').first()).toBeVisible();
  await this.captureOwnMeme();
});

Then('their meme is gone from the wall', async function () {
  // the whole point of the saga: the identity going is not enough, the content must follow
  await this.eventually(async () => {
    const r = await fetch(`${MEMES}/memes/${this.uploadedMemeId}/meta`);
    expect(r.status, 'the burnt meme is gone from the service, not just from the page').toBe(404);
  });
  await this.page.reload();
  await expect(this.page.locator(`img[src*="${this.uploadedMemeId}/thumbnail"]`)).toHaveCount(0);
});

Then('the comment {string} is gone from the thread', async function (text) {
  // The scenario this replaced asserted the opposite — the comment surviving, signed "deleted
  // account" — because a leaver used to CHOOSE what happened to what they wrote. Since the two
  // ways out of an account were separated, a closure somebody asks for themselves destroys
  // everything and offers no rule; anonymising is a condition only an ADMIN may attach when
  // closing SOMEBODY ELSE's account. The assertion moved with the product.
  //
  // Read through the whole thread, not page 0: the purge is a saga hop away and this suite
  // shares one meme, so "not on the first page" would pass for a comment that is merely late.
  await this.eventually(async () => {
    const thread = await (await fetch(`${COMMENTS}/memes/${this.memeId}/comments?page=0&size=100`)).json();
    const mine = (thread.comments ?? thread).find((c) => c.text === text);
    expect(mine, `the comment "${text}" belonged to an account that is gone, so it goes too`)
      .toBeFalsy();
  });
});

Then('signing in with that account still works', async function () {
  const r = await this.post(`${SECURITY}/authenticate`, this.account);
  expect([200, 202]).toContain(r.status);
});
