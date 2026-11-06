import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import AdminPanel from './AdminPanel';

/**
 * What the gallery says when a service ANSWERS — and answers something this UI could not read.
 *
 * serviceDown.test.tsx sits next door and covers the other half: `fetch` rejecting because nothing
 * is listening. These cases are harder to see, because the wire is healthy. The server names its
 * refusal — in a status it never used before, in a body written for the person holding the mouse —
 * and the screen showed a bare number, somebody else's fault, or nothing whatsoever.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** The reads a signed-in gallery makes on its own; `refuse` decides what happens to the writes. */
const gallery = (refuse: (url: string, init?: RequestInit) => Response | undefined) =>
  vi.stubGlobal('fetch', vi.fn((input: unknown, init?: RequestInit) => {
    const url = String(input);
    const refused = refuse(url, init);
    if (refused !== undefined) return Promise.resolve(refused);
    if (url.startsWith('/memes/scores')) return Promise.resolve(json([]));
    if (url.startsWith('/memes?')) return Promise.resolve(json([{ id: 'm-0', nsfw: false }]));
    if (url.endsWith('/me')) return Promise.resolve(json({ email: 'ala@example.com', roles: [] }));
    if (url.endsWith('/collections/favourites/items')) return Promise.resolve(json([]));
    if (url.endsWith('/meta')) return Promise.resolve(json({ id: 'm-0', author: 'a***@example.com' }));
    if (url.endsWith('/votes')) return Promise.resolve(json({ score: 0, myVote: null }));
    if (url.includes('/comments')) return Promise.resolve(json([]));
    if (url.endsWith('/tags')) return Promise.resolve(json([]));
    return Promise.resolve(json({}, 404));
  }));

const openTheMeme = async (container: HTMLElement) => {
  await waitFor(() => expect(container.querySelector('img[src^="/memes/m-0/thumbnail"]')).toBeInTheDocument());
  fireEvent.click(container.querySelector('img[src^="/memes/m-0/thumbnail"]')!);
};

describe('a 401 that outlives the token refresh', () => {
  beforeEach(() => localStorage.setItem('accessToken', 'an-hour-old'));
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  it('is said out loud instead of leaving the arrow where it was', async () => {
    gallery((url, init) => {
      // the refresh SUCCEEDS — the cookie is good and security mints a new access token — and the
      // service refuses the new token all the same. Nothing in this app spoke for that case: every
      // caller reads a 401 as "the session died and App has already said so"
      if (url.endsWith('/refresh')) return json({ accessToken: 'brand-new' });
      if (url.endsWith('/votes') && init?.method === 'POST') return json({ status: 'SIGN_IN_REQUIRED' }, 401);
      return undefined;
    });
    const { container } = render(<App />);

    await openTheMeme(container);
    fireEvent.click(await screen.findByRole('button', { name: 'vote up' }));

    expect(await screen.findByText(/sign-in could not be confirmed/i)).toBeInTheDocument();
  });
});

describe('a write refused because memes cannot reach security', () => {
  beforeEach(() => localStorage.setItem('accessToken', 'a-live-token'));
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  it('says the session is fine, because that is exactly what the new code means', async () => {
    gallery((url, init) => (url.endsWith('/votes') && init?.method === 'POST'
      ? json({ status: 'SECURITY_UNAVAILABLE', detail: 'the sign-in service could not be reached' }, 503)
      : undefined));
    const { container } = render(<App />);

    await openTheMeme(container);
    fireEvent.click(await screen.findByRole('button', { name: 'vote up' }));

    // RequireSignInFilter answers 503 rather than 401 precisely so a security outage stops
    // destroying live sessions; "(503)" under a vote that did not move says the opposite
    expect(await screen.findByText(/still signed in/i)).toBeInTheDocument();
  });
});

describe('an upload the gallery refuses', () => {
  beforeEach(() => localStorage.setItem('accessToken', 'a-live-token'));
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  const pickAFile = (container: HTMLElement) =>
    fireEvent.change(container.querySelector('input[type="file"]')!, {
      target: { files: [new File(['xx'], 'holiday.jpg', { type: 'image/jpeg' })] },
    });

  it('repeats the limit the server stated instead of printing 413', async () => {
    gallery((url, init) => (url === '/memes' && init?.method === 'POST'
      ? json({ status: 'TOO_LARGE', detail: 'the upload is 14680064 bytes; this service accepts at most 10485760' }, 413)
      : undefined));
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('input[type="file"]')).toBeInTheDocument());

    pickAFile(container);

    expect(await screen.findByText(/accepts at most 10485760/)).toBeInTheDocument();
  });

  it('repeats the image pipeline’s own sentence, which was written for the uploader', async () => {
    gallery((url, init) => (url === '/memes' && init?.method === 'POST'
      ? json({ error: 'unsupported or unreadable image' }, 400)
      : undefined));
    const { container } = render(<App />);
    await waitFor(() => expect(container.querySelector('input[type="file"]')).toBeInTheDocument());

    pickAFile(container);

    expect(await screen.findByText(/unsupported or unreadable image/)).toBeInTheDocument();
  });
});

describe('the sign-in panel in front of a broken security service', () => {
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  it('does not blame the password for a fault that is not the caller’s', async () => {
    gallery((url, init) => (url.endsWith('/authenticate') && init?.method === 'POST'
      ? json({ error: 'internal error' }, 500)
      : undefined));
    render(<App />);

    fireEvent.change(await screen.findByLabelText(/e-mail/i), { target: { value: 'ala@example.com' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'Correct-Horse-9' } });
    fireEvent.click(screen.getByRole('button', { name: /^sign in$/i }));

    // security refuses with 401, 403 and 429 and nothing else, so a 500 is its database or a
    // gateway — and the person retypes a correct password until the throttle locks them out
    expect(await screen.findByText(/not about your password/i)).toBeInTheDocument();
    expect(screen.queryByText('Wrong e-mail or password.')).toBeNull();
  });
});

describe('the admin dial when the policy read is REFUSED', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('admits it, rather than presenting its own default as the rule in force', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({ status: 'NOT_AN_ADMIN' }, 403))));
    render(<AdminPanel token="t" open onClose={() => {}} />);

    // a refused read comes back as null, which used to do nothing but hide the "Effective:" alert
    // while the select kept its initial DELETE — and "Save override" then wrote a rule nobody chose
    const said = await screen.findByText(/NOT what is in force/i);
    // and it is not good news: a green alert over an unread policy is the same lie in a colour
    expect(said.closest('.MuiAlert-root')).toHaveClass('MuiAlert-colorWarning');
  });
});
