import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import AdminPanel from './AdminPanel';

/**
 * What the gallery says when a service is simply NOT THERE.
 *
 * Every screen here was written against a server that answers — 401, 403, 429, a refusal carrying a
 * code — and each of those has its own sentence. But `fetch` does not answer with a status when the
 * other side is down: it REJECTS. That rejection travelled up through handlers typed `() => void`,
 * past `try/finally` blocks with no `catch`, and out of the component as an unhandled promise
 * rejection. On the screen: nothing at all. The button came back, the dialog stayed open, and the
 * user pressed it again.
 *
 * These tests reject `fetch` the way a stopped container does, and assert that a sentence reaches
 * the screen. Take the `catch` back out of the component under test and the matching case goes red
 * — which is the only reason to keep them.
 */

const down = () => vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

describe('when the service behind a write is down', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('the sign-in form says so instead of quietly re-enabling itself', async () => {
    down();
    render(<App />);

    fireEvent.change(await screen.findByLabelText(/e-mail/i), { target: { value: 'ala@example.com' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'Correct-Horse-9' } });
    fireEvent.click(screen.getByRole('button', { name: /^sign in$/i }));

    expect(await screen.findByText(/could not reach the sign-in service/i)).toBeInTheDocument();
  });

  it('the admin dial admits it could not read the policy it is showing', async () => {
    down();
    // the dial falls back to its initial 'DELETE' when the read fails; saying nothing would present
    // that default to a moderator as the rule actually in force
    render(<AdminPanel token="t" open onClose={() => {}} />);

    expect(await screen.findByText(/NOT what is in force/i)).toBeInTheDocument();
  });

  it('the admin dial says the override was NOT saved, rather than nothing', async () => {
    down();
    render(<AdminPanel token="t" open onClose={() => {}} />);

    fireEvent.click(await screen.findByRole('button', { name: /save override/i }));

    expect(await screen.findByText(/was NOT saved/i)).toBeInTheDocument();
  });
});
