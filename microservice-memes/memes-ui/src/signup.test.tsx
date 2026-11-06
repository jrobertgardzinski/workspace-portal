import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import { SECURITY } from './api';

/**
 * A refused registration must SAY why. Security answers 422 with one object per broken rule,
 * {CODE: parameter} — the parameter is the policy value in force for this very attempt, because
 * the minimum length is live configuration an ADMIN can move while the system runs. The UI once
 * typed these as strings and lowercased an object: a TypeError inside signUp, a rejected promise
 * nobody caught, and a form that silently did nothing — exactly what the film showed. Both
 * channels carry the same shape, so both are read by the same code.
 */

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const stubFetch = (register: () => Response) => {
  vi.stubGlobal('fetch', (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === `${SECURITY}/register`) return Promise.resolve(register());
    if (url.startsWith('/memes')) return Promise.resolve(json([]));
    if (url === `${SECURITY}/oauth/providers`) return Promise.resolve(json({ providers: [] }));
    return Promise.resolve(json({}));
  });
};

const signUp = async (email: string, password: string) => {
  render(<App />);
  fireEvent.click(await screen.findByText('Create account', { selector: '[role="tab"]' }));
  fireEvent.change(screen.getByLabelText(/e-mail/i), { target: { value: email } });
  fireEvent.change(screen.getByLabelText(/password/i), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
};

describe('a refused registration names every rule it broke', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('groups the errors under their field, each code carrying the parameter in force', async () => {
    stubFetch(() =>
      json(
        {
          emailErrors: [{ DOMAIN_MISSING_DOT: true }],
          passwordErrors: [{ MIN_LENGTH_NOT_MET: 10 }, { DIGIT_REQUIRED: true }],
        },
        422,
      ),
    );

    // the address must pass the browser's own type="email" check or the form never submits —
    // the e-mail error under test is the SERVER's verdict, stubbed above
    await signUp('taken@example.com', 'short');

    expect(await screen.findByText(/That will not do/)).toBeInTheDocument();
    const emailSection = screen.getByText('e-mail', { selector: 'strong' }).parentElement!;
    expect(emailSection).toHaveTextContent('domain missing dot');
    const passwordSection = screen.getByText('password', { selector: 'strong' }).parentElement!;
    expect(passwordSection).toHaveTextContent('min length not met: 10');
    expect(passwordSection).toHaveTextContent('digit required');
    expect(passwordSection).not.toHaveTextContent('domain missing dot');
  });

  it('shows only the field that was refused', async () => {
    stubFetch(() => json({ emailErrors: [], passwordErrors: [{ UPPERCASE_REQUIRED: true }] }, 422));

    await signUp('fine@example.com', 'lowercase only');

    expect(await screen.findByText('uppercase required')).toBeInTheDocument();
    expect(screen.queryByText('e-mail', { selector: 'strong' })).not.toBeInTheDocument();
  });
});
