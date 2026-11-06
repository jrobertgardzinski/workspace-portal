import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import DeleteAccountDialog from './DeleteAccountDialog';

/**
 * What the wizard says when the step-up did not succeed — and whether it lets the person try again.
 *
 * Every non-2xx used to read "Wrong password.", so a throttled attempt or a 500 accused the person
 * of mistyping a password that was right; and a request that never arrived threw straight past
 * `setBusy(false)`, leaving every button disabled with no message at all — the only way out was
 * reloading the page (P18 poz. 40). The 429 case is not hypothetical any more: step-up gained a
 * rate limit in P18 poz. 5, so "wait a moment" is now a routine answer that must not be dressed
 * up as a wrong password.
 */
describe('the deletion wizard when the step-up fails', () => {
  const original = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = original;
    vi.restoreAllMocks();
  });

  const open = () =>
    render(<DeleteAccountDialog token="t" email="leaver@example.com" onDeleted={() => {}} onClose={() => {}} />);

  const typePasswordAndSubmit = () => {
    fireEvent.change(screen.getByLabelText('your password'), { target: { value: 'right-one' } });
    fireEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
  };

  it('tells a throttled person to wait instead of blaming their password', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 429 }));
    open();

    typePasswordAndSubmit();

    await waitFor(() =>
      expect(screen.getByText('Too many attempts — wait a moment and try again.')).toBeTruthy());
    expect(screen.queryByText('Wrong password.')).toBeNull();
  });

  it('names a server fault as a server fault', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 500 }));
    open();

    typePasswordAndSubmit();

    await waitFor(() =>
      expect(screen.getByText('Security answered 500. Please try again.')).toBeTruthy());
    expect(screen.queryByText('Wrong password.')).toBeNull();
  });

  it('stays usable when the request never arrives', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    open();

    typePasswordAndSubmit();

    await waitFor(() => expect(screen.getByText(/Could not reach the security service/)).toBeTruthy());
    // the button must be clickable again: a network blip cannot cost the person their wizard
    expect(screen.getByRole('button', { name: 'Delete my account' })).not.toBeDisabled();
  });
});

/**
 * Which refusal it was — a question the STATUS cannot answer.
 *
 * StepUpController spends one 401 on four different outcomes: a wrong password, a wrong code, a
 * code chain that has run out of attempts and a ticket that is no longer valid. Only the body
 * names which. Grading the answer by its number alone told somebody with a dead ticket that their
 * code was wrong, so they typed another one into a chain that was already over — the same defect
 * as "Wrong password." for a 500, one layer further in.
 */
describe('the deletion wizard reading WHICH refusal it was', () => {
  const original = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = original;
    vi.restoreAllMocks();
  });

  /** Answers the password step with `first`, and the code step (if reached) with `then`. */
  const security = (first: { status: number; body: unknown }, then?: { status: number; body: unknown }) => {
    globalThis.fetch = vi.fn((input: unknown) => {
      const url = String(input);
      // a 401 sends `request` off to renew the token first; this session has no live cookie
      if (url.endsWith('/refresh')) return Promise.resolve(new Response('{}', { status: 401 }));
      const answer = url.endsWith('/step-up/factor') && then ? then : first;
      return Promise.resolve(new Response(JSON.stringify(answer.body), { status: answer.status }));
    }) as unknown as typeof globalThis.fetch;
  };

  const open = () =>
    render(<DeleteAccountDialog token="t" email="leaver@example.com" onDeleted={() => {}} onClose={() => {}} />);

  const typePasswordAndSubmit = () => {
    fireEvent.change(screen.getByLabelText('your password'), { target: { value: 'right-one' } });
    fireEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
  };

  it('still says "Wrong password." when that is what security said', async () => {
    security({ status: 401, body: { status: 'WRONG_PASSWORD' } });
    open();

    typePasswordAndSubmit();

    await waitFor(() => expect(screen.getByText('Wrong password.')).toBeTruthy());
  });

  it('sends somebody with no second factor to enrol one instead of repeating 409 at them', async () => {
    security({ status: 409, body: { status: 'ENROL_A_FACTOR_FIRST' } });
    open();

    typePasswordAndSubmit();

    await waitFor(() => expect(screen.getByText(/no second factor to confirm with/)).toBeTruthy());
    expect(screen.queryByText('Security answered 409. Please try again.')).toBeNull();
  });

  it('closes a spent code chain instead of asking for one more code', async () => {
    security(
      { status: 202, body: { status: 'FACTOR_REQUIRED', stepUpTicket: 'tk-1' } },
      { status: 401, body: { status: 'TOO_MANY_ATTEMPTS' } },
    );
    open();

    typePasswordAndSubmit();
    fireEvent.change(await screen.findByLabelText('sign-in code'), { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm & delete' }));

    await waitFor(() => expect(screen.getByText(/that confirmation is closed/i)).toBeTruthy());
    expect(screen.queryByText('Wrong code.')).toBeNull();
    // and the way forward is the password again — the ticket accepts nothing any more
    expect(screen.getByLabelText('your password')).toBeTruthy();
  });

  it('counts down the tries security is still willing to give', async () => {
    security(
      { status: 202, body: { status: 'FACTOR_REQUIRED', stepUpTicket: 'tk-1' } },
      { status: 401, body: { status: 'WRONG_CODE', attemptsLeft: 2 } },
    );
    open();

    typePasswordAndSubmit();
    fireEvent.change(await screen.findByLabelText('sign-in code'), { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm & delete' }));

    await waitFor(() => expect(screen.getByText('Wrong code — 2 tries left.')).toBeTruthy());
  });
});

/**
 * What the wizard puts ON THE WIRE — and what it no longer asks.
 *
 * It used to offer three options, one of which kept the memes the community had up-voted. Closing
 * your own account is the right to be forgotten and that right has no exception for popular
 * content, so the options are gone: the route takes no body at all, and every content service
 * discards a rule it finds on a self-requested closure anyway. Conditions live on the ADMIN route,
 * where nobody is exercising a right.
 */
describe('what the wizard sends', () => {
  const original = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = original;
    vi.restoreAllMocks();
  });

  /** Answers ELEVATED to the step-up and 202 to the deletion, recording every call. */
  const stubSecurity = () => {
    const calls: Array<{ url: string; body: unknown }> = [];
    globalThis.fetch = vi.fn((input: unknown, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, body: init?.body === undefined ? undefined : JSON.parse(String(init.body)) });
      return Promise.resolve(url.endsWith('/account/leaver%40example.com')
        ? new Response('{}', { status: 202 })
        : new Response(JSON.stringify({ status: 'ELEVATED' }), { status: 200 }));
    }) as unknown as typeof globalThis.fetch;
    return calls;
  };

  const deleteCall = (calls: Array<{ url: string; body: unknown }>) =>
    calls.find((call) => call.url.endsWith('/account/leaver%40example.com'));

  const runWizard = async () => {
    const calls = stubSecurity();
    const deleted = vi.fn();
    render(<DeleteAccountDialog token="t" email="leaver@example.com" onDeleted={deleted} onClose={() => {}} />);
    fireEvent.change(screen.getByLabelText('your password'), { target: { value: 'right-one' } });
    fireEvent.click(screen.getByRole('button', { name: 'Delete my account' }));
    await waitFor(() => expect(deleted).toHaveBeenCalled());
    return calls;
  };

  it('sends no body at all — there is no condition left to state', async () => {
    expect(deleteCall(await runWizard())?.body).toBeUndefined();
  });

  it('offers the person nothing to choose', async () => {
    stubSecurity();
    render(<DeleteAccountDialog token="t" email="leaver@example.com" onDeleted={() => {}} onClose={() => {}} />);

    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    expect(screen.getByText(/everything you posted goes with it/)).toBeTruthy();
  });
});
