import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import { SECURITY } from './api';

/**
 * Signing out must end the session SERVER-side, not just forget the token.
 *
 * P12 W1 named this very file, yet the `POST /logout` call only ever reached security-ui — here
 * "sign out" stayed `setToken(null)`, so the rotating HttpOnly refresh cookie remained valid for
 * ~a day. On a shared computer the next person needed one `fetch(SECURITY + '/refresh',
 * {credentials:'include'})` to inherit the previous user's session, roles included (P18 poz. 7).
 * These tests pin the two moments the session ends in this UI: the sign-out click, and the
 * refresh-has-failed path — both must tell the server, and the cookie must ride along.
 */

type Recorded = { url: string; init?: RequestInit };

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** Answers every call App makes on mount; `overrides` swap single endpoints per test. */
const stubFetch = (overrides: Record<string, () => Response> = {}): Recorded[] => {
  const calls: Recorded[] = [];
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    for (const [prefix, respond] of Object.entries(overrides)) {
      if (url === prefix || url.startsWith(`${prefix}?`)) return Promise.resolve(respond());
    }
    if (url.startsWith('/memes')) return Promise.resolve(json([]));
    if (url === `${SECURITY}/me`) return Promise.resolve(json({ email: 'alice@example.com', roles: [] }));
    if (url === `${SECURITY}/oauth/providers`) return Promise.resolve(json({ providers: [] }));
    if (url.includes('/collections/favourites/items')) return Promise.resolve(json([]));
    return Promise.resolve(json({}));
  });
  return calls;
};

const logoutCallIn = (calls: Recorded[]): Recorded | undefined =>
  calls.find((c) => c.url === `${SECURITY}/logout`);

describe('ending the session tells the server, not only this tab', () => {
  beforeEach(() => localStorage.setItem('accessToken', 'live-token'));
  afterEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it('sign out POSTs /logout with the cookie riding along', async () => {
    const calls = stubFetch();
    render(<App />);
    await screen.findByText(/signed in as alice@example.com/);

    fireEvent.click(screen.getByText('sign out'));

    await waitFor(() => expect(logoutCallIn(calls)).toBeDefined());
    // the refresh token lives ONLY in the HttpOnly cookie — without credentials the request
    // reaches the server empty-handed and revokes nothing
    expect(logoutCallIn(calls)!.init).toMatchObject({ method: 'POST', credentials: 'include' });
    // and the local half still happened: the chip is gone
    expect(screen.queryByText(/signed in as/)).not.toBeInTheDocument();
  });

  it('a session that fails to refresh is also ended server-side — the cookie may well be alive', async () => {
    // /me answers 401 and the refresh does not come back: `request` gives up, session.expired fires
    const calls = stubFetch({
      [`${SECURITY}/me`]: () => json({}, 401),
      [`${SECURITY}/refresh`]: () => json({}, 401),
    });
    render(<App />);

    await screen.findByText(/Your session expired/);
    await waitFor(() => expect(logoutCallIn(calls)).toBeDefined());
    expect(logoutCallIn(calls)!.init).toMatchObject({ method: 'POST', credentials: 'include' });
  });
});
