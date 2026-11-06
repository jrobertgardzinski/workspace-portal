/** Talking to the two services: memes (same origin) and security (CORS, token issuer). */

/**
 * Addresses handed to the page by whoever DEPLOYED it, ahead of the ones baked into the bundle.
 *
 * Vite substitutes `import.meta.env.VITE_*` at build time, so the bundle - and therefore the jar
 * and the container image that carries it - had exactly one set of API addresses in it: compose's.
 * Served from a Kubernetes cluster the same page told the browser to call `localhost:8080`, which
 * is the developer's own laptop, and the gallery came up looking perfectly healthy while nobody
 * could sign in.
 *
 * `window.__PORTAL_CONFIG__` is written by `/ui-config.js`, a classic script in index.html that the
 * browser runs before this module. The chain falls back to the baked value and then to compose, so
 * `npm run dev` with no server behind it behaves exactly as it always did.
 */
declare global {
  interface Window {
    __PORTAL_CONFIG__?: {
      securityUrl?: string;
      commentsUrl?: string;
      collectionsUrl?: string;
    };
  }
}

const deployed = (key: 'securityUrl' | 'commentsUrl' | 'collectionsUrl'): string | undefined =>
  typeof window === 'undefined' ? undefined : window.__PORTAL_CONFIG__?.[key];

export const SECURITY: string =
  deployed('securityUrl') ?? import.meta.env.VITE_SECURITY_URL ?? 'http://localhost:8080';

/** Comments live in their own microservice. */
export const COMMENTS: string =
  deployed('commentsUrl') ?? import.meta.env.VITE_COMMENTS_URL ?? 'http://localhost:8085';

export interface MemeRef {
  id: string;
  /** the moderators' judgement — the gallery blurs flagged memes until deliberately revealed */
  nsfw?: boolean;
}

/** A meme id with its vote score — how the score reads answer on the wire. */
export interface ScoreEntry {
  memeId: string;
  score: number;
}

export interface MemeComment {
  id: string;
  /** masked in the public listing (a***@example.com) — display it, never compare it to an identity */
  author: string;
  /** null for a reader when a moderator has hidden the comment (the author still sees their own) */
  text: string | null;
  /** null when the backend has no tally to report — render "n/a", never a made-up 0 */
  score: number | null;
  myVote: VoteDirection | null;
  /** a moderator's soft flag: the comment shows as a tombstone rather than being deleted */
  hidden?: boolean;
  /** the server's word that the signed-in viewer wrote this — the ONLY basis for "your comment"
   *  affordances, since the masked author no longer equals anyone's e-mail (absent on older APIs) */
  own?: boolean;
}

export type VoteDirection = 'UP' | 'DOWN';

/** A target's score plus the caller's own current vote (null = not voted). */
export interface VoteTally {
  /** the JSON field is nullable on the wire (the backend builds it with map.put) — a null must
   *  surface as "n/a" in the UI, not silently coerce into a numeric-looking 0 */
  score: number | null;
  myVote: VoteDirection | null;
}

/** A user-facing outcome message; `items` carries policy violations from a 422. */
export interface Notice {
  tone: 'success' | 'warning';
  text: string;
  items?: string[];
  /** Grouped items, one group per field — a refused registration answers per field, so is shown per field. */
  sections?: { title: string; items: string[] }[];
}

export const jsonHeaders = { 'Content-Type': 'application/json' };

export const authHeader = (token: string | null): Record<string, string> =>
  token ? { Authorization: `Bearer ${token}` } : {};

/**
 * A backend answer that is not the answer we asked for. Reads throw this instead of handing a
 * Spring error object to a caller that is about to call `.map` on it — one 5xx from the comment
 * service used to unmount the whole React root, gallery and sign-in panel included.
 */
export class HttpError extends Error {
  constructor(readonly status: number, detail?: string) {
    super(detail ?? `HTTP ${status}`);
    this.name = 'HttpError';
  }
}

/**
 * What the wrapper cannot decide on its own: the access token has been renewed (App must keep it,
 * localStorage and all), the session is really gone (App must forget it and say so), or a service
 * refused a token security had just minted — the session lives, this one write did not happen, and
 * nothing else in the app was ever going to mention it. Wired once, from App — deliberately not a
 * context or a store, so nothing else has to change shape.
 */
let session = {
  renewed: (_token: string) => {},
  expired: () => {},
  refused: () => {},
};
export const bindSession = (handlers: typeof session) => { session = handlers; };

/**
 * One refresh at a time, shared by every 401 that arrives while it runs.
 *
 * The refresh token ROTATES on use (security's RefreshController issues a new cookie on every
 * success) and a presented-but-already-spent token is treated as a REPLAY —
 * RefreshSession.Result.ReuseDetected, which kills the session. Two parallel refreshes would therefore log the user out
 * for the crime of clicking two arrows quickly: hence one promise, and everybody waits for it.
 */
let refreshing: Promise<string | null> | null = null;

const renewAccessToken = (): Promise<string | null> => {
  refreshing ??= fetch(`${SECURITY}/refresh`, { method: 'POST', credentials: 'include' })
    // the refresh token travels ONLY in the HttpOnly cookie, which is why this one call needs
    // credentials:'include' — nothing in the page can see or send that token itself
    .then((r) => (r.ok ? r.json() as Promise<{ accessToken?: string }> : null))
    .then((body) => body?.accessToken ?? null)
    .catch(() => null)
    .finally(() => { refreshing = null; });
  return refreshing;
};

/**
 * Ends the session server-side. "Sign out" used to be `setToken(null)` alone (P12 W1 named this
 * very file), so the rotating HttpOnly refresh cookie stayed valid for ~a day — on a shared
 * computer one `POST /refresh` handed the next person the previous user's session. The cookie
 * rides along on credentials:'include' and the whole session family dies; `keepalive` lets the
 * request outlive the click; a network error is swallowed, because signing out locally must
 * never hang on the server. Without the cookie it is an idempotent no-op.
 */
export const logout = (): void => {
  void fetch(`${SECURITY}/logout`, { method: 'POST', credentials: 'include', keepalive: true })
    .catch(() => { /* the local sign-out proceeds regardless */ });
};

const headersOf = (init: RequestInit): Record<string, string> =>
  ({ ...(init.headers as Record<string, string> | undefined) });

/**
 * Every call to every service goes through here — and this is the ONE place that knows what a 401
 * means: the access token lived an hour, the refresh token lives a day, so try once to trade the
 * cookie for a new access token and repeat the request. Only a request that carried an
 * Authorization header is worth retrying; a public GET answering 401 is the server's business.
 *
 * Non-ok answers are returned as they are (callers that read `.ok` keep working); the reads below
 * turn them into an HttpError.
 */
export const request = async (url: string, init: RequestInit = {}): Promise<Response> => {
  const response = await fetch(url, init);
  if (response.status !== 401 || !headersOf(init).Authorization) return response;
  const renewed = await renewAccessToken();
  if (!renewed) {
    session.expired();
    return response;
  }
  session.renewed(renewed);
  const retried = await fetch(url, {
    ...init, headers: { ...headersOf(init), Authorization: `Bearer ${renewed}` },
  });
  // A 401 that OUTLIVES the renewal is not an expired session: security has just minted this very
  // token. It is the service in front of us refusing it — memes could not reach security to
  // introspect it, or comments is working from a key set a few seconds stale. Callers treat a 401
  // as "the session died and App has already said so", which on this path nobody had: the arrow
  // did not move, the score did not move, and not a word was said.
  if (retried.status === 401) session.refused();
  return retried;
};

/** A read that must come back as a JSON object, or not at all. */
const readObject = async <T>(url: string, init: RequestInit = {}): Promise<T> => {
  const r = await request(url, init);
  if (!r.ok) throw new HttpError(r.status);
  const body: unknown = await r.json();
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    throw new HttpError(r.status, `${url} answered ${r.status} with something that is not an object`);
  }
  return body as T;
};

/**
 * A read that must come back as a JSON ARRAY, or not at all — the shape check is the point.
 * Spring's error bodies are objects, and an object reaching `setComments` is exactly how a broken
 * comment database used to take the gallery down with it.
 */
const readArray = async <T>(url: string, init: RequestInit = {}): Promise<T[]> => {
  const r = await request(url, init);
  if (!r.ok) throw new HttpError(r.status);
  const body: unknown = await r.json();
  if (!Array.isArray(body)) {
    throw new HttpError(r.status, `${url} answered ${r.status} with something that is not a list`);
  }
  return body as T[];
};

/** The wall is served one page at a time; the server caps the size, so this is what a page is. */
export const GALLERY_PAGE_SIZE = 50;

/** How many memes one score call asks about; the server refuses more than a page of the wall, so a
 *  longer list (a well-stocked favourites collection) goes out as several calls. */
export const SCORE_BATCH_SIZE = GALLERY_PAGE_SIZE;

export const listMemes = async (tag?: string, page = 0): Promise<MemeRef[]> => {
  const query = new URLSearchParams({ page: String(page), size: String(GALLERY_PAGE_SIZE) });
  if (tag) query.set('tag', tag);
  return readArray<MemeRef>(`/memes?${query}`);
};

/** A meme's public metadata. The uploader comes back MASKED (a***@example.com) — display it,
 *  never compare it to an identity; `own` is the server's answer to "is this mine?", computed
 *  from the token this call carries (false for a signed-out visitor, who may still read). */
export const memeMeta = async (
  memeId: string, token: string | null,
): Promise<{ id: string; author: string; own?: boolean; nsfw?: boolean }> =>
  readObject(`/memes/${memeId}/meta`, { headers: authHeader(token) });

/** Flag or unflag a meme NSFW — a moderator-only call; the backend is the authority. The status
 *  comes back because 403 (not a moderator) and 404 (the meme went) are different sentences. */
export const setMemeNsfw = async (
  memeId: string, nsfw: boolean, token: string | null,
): Promise<{ ok: boolean; status: number }> => {
  const r = await request(`/memes/${memeId}/nsfw`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeader(token) },
    body: JSON.stringify({ nsfw }),
  });
  return { ok: r.ok, status: r.status };
};

/** The tags an uploader has put on a meme (sorted). */
export const memeTags = async (memeId: string): Promise<string[]> =>
  readArray<string>(`/memes/${memeId}/tags`);

/** Replace a meme's whole tag set (author only). Returns the accepted tags, or a status on refusal. */
export const setMemeTags = async (
  memeId: string, tags: string[], token: string | null,
): Promise<{ ok: boolean; status?: string; tags?: string[] }> => {
  const r = await request(`/memes/${memeId}/tags`, {
    method: 'POST',
    headers: { ...jsonHeaders, ...authHeader(token) },
    body: JSON.stringify({ tags }),
  });
  const body = await r.json().catch(() => ({}));
  return r.ok ? { ok: true, tags: body.tags } : { ok: false, status: body.status ?? String(r.status) };
};

/**
 * The scores of EXACTLY these memes, keyed by id — the read behind the numbers on the tiles.
 *
 * A meme missing from the returned map has an UNKNOWN score: the service had nothing to say about
 * that id (a favourite that outlived its meme), or a batch never got through. It is NOT a meme with
 * zero votes — an existing meme nobody voted on comes back with a real 0. That is why this answers
 * with a Map: `get` returns `undefined`, so a caller has to decide what "no tally" looks like
 * instead of indexing into an object and writing `?? 0`.
 *
 * The wall used to take its numbers from `/memes/hot`. That list is a RANKING capped at the hottest
 * hundred, so a lookup for anything below the cap found nothing — and `?? 0` printed "▲ 0" under
 * memes that had votes. Today's gallery is smaller than the cap, which is the only reason nobody
 * had seen it yet.
 */
export const memeScores = async (memeIds: string[]): Promise<Map<string, number>> => {
  const scores = new Map<string, number>();
  for (let from = 0; from < memeIds.length; from += SCORE_BATCH_SIZE) {
    const batch = memeIds.slice(from, from + SCORE_BATCH_SIZE);
    const r = await request(`/memes/scores?ids=${batch.map(encodeURIComponent).join(',')}`);
    // a batch that did not answer leaves those ids out of the map, which reads as "unknown" —
    // the one thing we must not do here is fill the gap with zeros
    if (!r.ok) continue;
    const body: unknown = await r.json();
    if (!Array.isArray(body)) continue;          // same rule: an error object is not a tally
    (body as ScoreEntry[]).forEach((entry) => scores.set(entry.memeId, entry.score));
  }
  return scores;
};

/**
 * A thread is paged, and it is paged OLDEST FIRST (comments ORDER BY created_at). The UI used to
 * send no page at all and take whatever came — the first 50, i.e. the oldest 50 — which is why a
 * comment posted to a busy thread was invisible to its own author. This asks for the biggest page
 * the server will serve (CommentController.MAX_PAGE_SIZE); the caller walks forward from there.
 */
export const COMMENTS_PAGE_SIZE = 100;

export const listComments = async (
  memeId: string, token: string | null, page = 0,
): Promise<MemeComment[]> =>
  readArray<MemeComment>(
    `${COMMENTS}/memes/${memeId}/comments?page=${page}&size=${COMMENTS_PAGE_SIZE}`,
    { headers: authHeader(token) });

/** Post a comment; the 201 carries the new comment's id, which is how the thread can go find it. */
export const postComment = async (
  memeId: string, text: string, token: string | null,
): Promise<{ ok: boolean; id?: string; status: number; detail?: string }> => {
  const r = await request(`${COMMENTS}/memes/${memeId}/comments`, {
    method: 'POST',
    headers: { ...jsonHeaders, ...authHeader(token) },
    body: JSON.stringify({ text }),
  });
  const body = await r.json().catch(() => ({} as { id?: string; status?: string }));
  return r.ok
    ? { ok: true, id: body.id, status: r.status }
    : { ok: false, status: r.status, detail: body.status };
};

export const memeTally = async (memeId: string, token: string | null): Promise<VoteTally> =>
  readObject(`/memes/${memeId}/votes`, { headers: authHeader(token) });

/** Cast (or retract) a vote on a meme. Returns the fresh tally, or the status that refused it. */
export const voteOnMeme = async (
  memeId: string, direction: VoteDirection, token: string | null,
): Promise<{ ok: boolean; tally?: VoteTally; status: number }> => {
  const r = await request(`/memes/${memeId}/votes`, {
    method: 'POST',
    headers: { ...jsonHeaders, ...authHeader(token) },
    body: JSON.stringify({ direction }),
  });
  return r.ok ? { ok: true, tally: await r.json() as VoteTally, status: r.status }
              : { ok: false, status: r.status };
};

/** Cast (or retract) a vote on a comment. Same contract as the meme vote above. */
export const voteOnComment = async (
  memeId: string, commentId: string, direction: VoteDirection, token: string | null,
): Promise<{ ok: boolean; tally?: VoteTally; status: number }> => {
  const r = await request(`${COMMENTS}/memes/${memeId}/comments/${commentId}/votes`, {
    method: 'POST',
    headers: { ...jsonHeaders, ...authHeader(token) },
    body: JSON.stringify({ direction }),
  });
  return r.ok ? { ok: true, tally: await r.json() as VoteTally, status: r.status }
              : { ok: false, status: r.status };
};

/** Delete a meme — the author (of their own) or a moderator (of anyone's). Server-authorised. */
export const deleteMeme = async (memeId: string, token: string | null): Promise<boolean> =>
  (await request(`/memes/${memeId}`, { method: 'DELETE', headers: authHeader(token) })).ok;

/** Delete a comment — its author, or a moderator. Server-authorised. */
export const deleteComment = async (
  memeId: string, commentId: string, token: string | null,
): Promise<{ ok: boolean; status: number }> => {
  const r = await request(`${COMMENTS}/memes/${memeId}/comments/${commentId}`,
    { method: 'DELETE', headers: authHeader(token) });
  // 404 = it is not there any more, which is what the click asked for; deleting is idempotent
  return { ok: r.ok || r.status === 404, status: r.status };
};

/** Hide or reveal a comment — a moderator-only soft touch (a tombstone, not a deletion). */
export const setCommentHidden = async (
  memeId: string, commentId: string, hidden: boolean, token: string | null,
): Promise<boolean> =>
  (await request(`${COMMENTS}/memes/${memeId}/comments/${commentId}/hidden`, {
    method: 'PUT',
    headers: { ...jsonHeaders, ...authHeader(token) },
    body: JSON.stringify({ hidden }),
  })).ok;

/** The purge-policy dial, admin-only: what happens to a leaver's memes when the closure named no rule. */
export interface PurgePolicy {
  axis: string;
  effective: string;
  source: 'DB' | 'ENV';
  envDefault: string;
}

export const getPurgePolicy = async (token: string | null): Promise<PurgePolicy | null> => {
  const r = await request('/admin/purge-policy', { headers: authHeader(token) });
  return r.ok ? r.json() : null;
};

export const setPurgePolicy = async (
  rule: string, token: string | null,
): Promise<{ ok: boolean; detail?: string }> => {
  const r = await request('/admin/purge-policy', {
    method: 'PUT',
    headers: { ...jsonHeaders, ...authHeader(token) },
    body: JSON.stringify({ memes: rule }),
  });
  const body = await r.json().catch(() => ({}));
  return r.ok ? { ok: true } : { ok: false, detail: body.detail ?? body.status ?? String(r.status) };
};

export const clearPurgePolicy = async (token: string | null): Promise<boolean> =>
  (await request('/admin/purge-policy', { method: 'DELETE', headers: authHeader(token) })).ok;

/** Favourites live in microservice-user-collections: opaque refs, hydrated by THIS gallery. */
export const COLLECTIONS: string =
  deployed('collectionsUrl') ?? import.meta.env.VITE_COLLECTIONS_URL ?? 'http://localhost:8092';

export interface FavouriteRef { itemType: string; itemId: string; }

/** The caller's saved refs, newest first. Throws an HttpError on anything but a 200 list — the
 *  caller has to tell "you have none" apart from "we could not find out". */
export const listFavourites = async (token: string): Promise<FavouriteRef[]> =>
  readArray<FavouriteRef>(`${COLLECTIONS}/collections/favourites/items`,
    { headers: authHeader(token) });

export const saveFavourite = async (memeId: string, token: string): Promise<boolean> =>
  (await request(`${COLLECTIONS}/collections/favourites/items/meme/${encodeURIComponent(memeId)}`,
    { method: 'PUT', headers: authHeader(token) })).ok;

/**
 * Un-star a meme. A 404 is a SUCCESS: the entry is not there, which is precisely what the click
 * asked for (the row may have gone in another tab, in collections-ui, or with a purge). Reporting
 * it as "the service did not answer" put the star back on screen and made every retry fail the
 * same way, with the server behaving perfectly throughout.
 */
export const removeFavourite = async (memeId: string, token: string): Promise<boolean> => {
  const r = await request(
    `${COLLECTIONS}/collections/favourites/items/meme/${encodeURIComponent(memeId)}`,
    { method: 'DELETE', headers: authHeader(token) });
  return r.ok || r.status === 404;
};
