import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';

/**
 * The WALL, as opposed to the dialog in front of it: what it keeps while somebody uses that dialog,
 * and what it knows about a meme it did not list itself.
 *
 * Both of these are invisible in a browser suite against a small stack. The first needs a gallery
 * bigger than one 50-tile page before the loss shows at all; the second needs a favourite that is
 * NOT on the page the wall happens to have loaded, which is the normal case for any real
 * collection and impossible to arrange reliably against a live wall of three memes.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const ids = (prefix: string, count: number) =>
  Array.from({ length: count }, (_, i) => `${prefix}-${i}`);

/** The whole estate the gallery talks to, answered from these tables. */
const stack = (given: {
  pages?: string[][];
  flagged?: string[];
  favourites?: string[];
  roles?: string[];
}) => {
  const pages = given.pages ?? [[]];
  const flagged = new Set(given.flagged ?? []);
  const favourites = given.favourites ?? [];
  let scoreReads = 0;
  const fetchStub = vi.fn((input: unknown, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.startsWith('/memes/scores')) {
      scoreReads += 1;
      return Promise.resolve(json([]));
    }
    if (url.startsWith('/memes?')) {
      const page = Number(new URLSearchParams(url.slice(url.indexOf('?'))).get('page') ?? '0');
      return Promise.resolve(json((pages[page] ?? []).map((id) => ({ id, nsfw: flagged.has(id) }))));
    }
    if (url.endsWith('/me')) {
      return Promise.resolve(json({ email: 'mod@example.com', roles: given.roles ?? [] }));
    }
    if (url.endsWith('/collections/favourites/items')) {
      return Promise.resolve(json(favourites.map((itemId) => ({ itemType: 'meme', itemId }))));
    }
    const meta = /^\/memes\/([^/?]+)\/meta$/.exec(url);
    if (meta) return Promise.resolve(json({ id: meta[1], author: 'a***@example.com', nsfw: flagged.has(meta[1]!) }));
    if (url.endsWith('/nsfw') && method === 'PUT') {
      const flag = JSON.parse(String(init?.body)).nsfw as boolean;
      return Promise.resolve(json({ nsfw: flag }));
    }
    if (url.endsWith('/votes')) {
      return Promise.resolve(method === 'POST' ? json({ score: 1, myVote: 'UP' }) : json({ score: 0, myVote: null }));
    }
    if (url.includes('/comments')) return Promise.resolve(json([]));
    if (url.endsWith('/tags')) return Promise.resolve(json([]));
    return Promise.resolve(json({}, 404));
  });
  vi.stubGlobal('fetch', fetchStub);
  return { scoreReads: () => scoreReads };
};

const tile = (container: HTMLElement, memeId: string) =>
  container.querySelector(`img[src^="/memes/${memeId}/thumbnail"]`);

/** The Card a tile sits in — the NSFW chip belongs to the tile, not to the wall. */
const cardOf = (container: HTMLElement, memeId: string) =>
  tile(container, memeId)!.closest('.MuiCard-root') as HTMLElement;

describe('the wall while somebody is using the dialog', () => {
  beforeEach(() => localStorage.setItem('accessToken', 'a-live-token'));
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  it('still holds every page that was loaded after a vote from inside a meme', async () => {
    const stubbed = stack({ pages: [ids('m', 50), ids('n', 50)] });
    const { container } = render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(tile(container, 'n-49')).toBeInTheDocument());

    fireEvent.click(tile(container, 'm-0')!);
    const before = stubbed.scoreReads();
    fireEvent.click(await screen.findByRole('button', { name: 'vote up' }));
    await waitFor(() => expect(screen.getByTestId('meme-score')).toHaveTextContent('1'));
    // the vote is answered by a fresh read of the SCORES; waiting for it is what makes this
    // assertion honest, because the old code re-listed the wall first and only then re-scored it
    await waitFor(() => expect(stubbed.scoreReads()).toBeGreaterThan(before));

    // onVoted used to be App.refresh, which sets page 0 and pagesShown=1: a visitor who had walked
    // to a hundred tiles was put back at fifty by one up-vote, scroll position and all
    expect(tile(container, 'n-49')).toBeInTheDocument();
  });

  it('shows the moderator the flag they have just set, without an unrelated refresh', async () => {
    stack({ pages: [['m-0']], roles: ['MODERATOR'] });
    const { container } = render(<App />);

    await waitFor(() => expect(tile(container, 'm-0')).toBeInTheDocument());
    fireEvent.click(tile(container, 'm-0')!);
    fireEvent.click(await screen.findByRole('button', { name: 'Flag NSFW' }));

    // the dialog kept the verdict to itself: the tile behind it went on saying the meme was clean
    // until something else happened to re-list the wall
    await waitFor(() => expect(within(cardOf(container, 'm-0')).getByText('NSFW')).toBeInTheDocument());
  });
});

describe('the favourites wall', () => {
  beforeEach(() => localStorage.setItem('accessToken', 'a-live-token'));
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  it('labels a flagged favourite the wall never listed, and only that one', async () => {
    // the wall page holds something else entirely — which is the normal case: it is one 50-tile
    // page, and it is tag-filtered whenever a filter is on
    stack({
      pages: [['m-0']],
      favourites: ['fav-flagged', 'fav-clean'],
      flagged: ['fav-flagged'],
    });
    const { container } = render(<App />);

    fireEvent.click(await screen.findByRole('button', { name: 'Favourites' }));

    // the clean one proves the flag was actually READ (it starts blurred and unblurs); the flagged
    // one is the defect: it used to resolve to `undefined` and render in the clear
    await waitFor(() => expect(within(cardOf(container, 'fav-clean')).queryByText('NSFW')).toBeNull());
    expect(within(cardOf(container, 'fav-flagged')).getByText('NSFW')).toBeInTheDocument();
  });
});
