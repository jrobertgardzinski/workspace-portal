import { useCallback, useEffect, useState } from 'react';
// imported rather than assumed: @types/react 19 stopped declaring JSX as a global
// namespace, so it has to come from the package now
import type { JSX } from 'react';
import Alert from '@mui/material/Alert';
import AppBar from '@mui/material/AppBar';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import CardActionArea from '@mui/material/CardActionArea';
import CardMedia from '@mui/material/CardMedia';
import Chip from '@mui/material/Chip';
import Container from '@mui/material/Container';
import IconButton from '@mui/material/IconButton';
import Snackbar from '@mui/material/Snackbar';
import Stack from '@mui/material/Stack';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import UploadIcon from '@mui/icons-material/Upload';
import SettingsIcon from '@mui/icons-material/Settings';
import StarIcon from '@mui/icons-material/Star';
import StarBorderIcon from '@mui/icons-material/StarBorder';
import AdminPanel from './AdminPanel';
import AuthPanel from './AuthPanel';
import MemeDialog from './MemeDialog';
import {
  authHeader, bindSession, GALLERY_PAGE_SIZE, listFavourites, listMemes, logout, memeMeta, MemeRef,
  memeScores, removeFavourite, request, saveFavourite, SECURITY,
} from './api';

/**
 * A refused upload, in the uploader's words.
 *
 * memes writes the explanation into the BODY on purpose — the size it will accept, why an image
 * was unreadable, how long to wait — and printing only the status threw all of it away: a 14 MB
 * photo read as "Upload refused (413)." with no mention of a limit. `error` is the image
 * pipeline's own sentence (WebErrorHandler), `detail` belongs to the coded refusals (TOO_LARGE,
 * MALFORMED_MULTIPART, RATE_LIMITED, BUSY, SECURITY_UNAVAILABLE). The per-status fallbacks are for
 * a refusal written by something in FRONT of the service, which answers HTML and no code at all.
 */
const uploadRefusal = (status: number, body: { error?: string; detail?: string }): string => {
  const said = body.error ?? body.detail;
  if (said) return `Upload refused — ${said}.`;
  if (status === 413) return 'Upload refused — that image is larger than the gallery accepts.';
  if (status === 429) return 'Upload refused — you are uploading too fast; wait a minute.';
  if (status === 503) return 'Upload refused — the gallery is unavailable right now; try again shortly.';
  return `Upload refused (${status}).`;
};

export default function App() {
  const [token, setToken] = useState<string | null>(localStorage.getItem('accessToken'));
  const [user, setUser] = useState('');
  const [isModerator, setIsModerator] = useState(false);
  const [isAdmin, setIsAdmin] = useState(false);
  const [adminOpen, setAdminOpen] = useState(false);
  const [memes, setMemes] = useState<MemeRef[]>([]);
  // the wall is paged (the server caps how much of the gallery one call may return), so the
  // visitor asks for the next page instead of the wall silently ending at the first one
  const [pagesShown, setPagesShown] = useState(1);
  const [moreToShow, setMoreToShow] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  // scores of the tiles on screen, keyed by meme id. A Map and not a Record on purpose: `get`
  // answers `undefined` for a meme whose score this UI does not know, and an unknown score must
  // stay visibly unknown — see ScoreChip. Nothing here may substitute a zero.
  const [scores, setScores] = useState<ReadonlyMap<string, number>>(new Map());
  const [selected, setSelected] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [tagFilter, setTagFilter] = useState<string | null>(null);
  // favourites live in microservice-user-collections: opaque meme refs this gallery hydrates.
  // null = signed out or not fetched yet. "The service did not answer" is a DIFFERENT state and
  // lives in favouritesError below — an empty list and an unanswered question must never render
  // as the same sentence, and they used to
  const [favourites, setFavourites] = useState<string[] | null>(null);
  const [favouritesError, setFavouritesError] = useState(false);
  const [showFavourites, setShowFavourites] = useState(false);
  // the wall itself can fail too; then it says so and offers a retry instead of looking empty
  const [wallError, setWallError] = useState(false);
  // the NSFW flag of memes the FAVOURITES wall shows. The wall's own listing carries the flag per
  // meme; favourites arrive from user-collections as bare ids and carry nothing, so they are asked
  // about separately — see the hydration effect below
  const [favouriteNsfw, setFavouriteNsfw] = useState<ReadonlyMap<string, boolean>>(new Map());

  const refresh = useCallback(() => {
    void listMemes(tagFilter ?? undefined, 0)
      .then((page) => {
        setMemes(page);
        setPagesShown(1);
        setWallError(false);
        // a full page is the only hint that more may follow — the listing carries no total
        setMoreToShow(page.length === GALLERY_PAGE_SIZE);
      })
      // an unreadable wall is an ERROR, not an empty gallery: "no memes yet — upload the first
      // one" in front of a service that is merely down is a lie about the whole product
      .catch(() => setWallError(true));
  }, [tagFilter]);
  useEffect(refresh, [refresh]);

  // The numbers under the tiles are asked for BY ID, for the tiles actually on screen. They used to
  // come out of /memes/hot, which is a ranking of the hottest hundred — every meme below the cap was
  // missing from it, and the missing entry rendered as "▲ 0" under a meme that had votes.
  //
  // Merged, never replaced: the wall and the favourites view are two sets of tiles, and learning one
  // set's scores must not turn the other's back into "unknown". A fresh answer overwrites the older
  // value for the same id, so a vote still moves the number — and re-asking about tiles we already
  // have a number for is the point, not waste: one query per fifty tiles buys a wall that is
  // current after every vote and every "Load more".
  //
  // It is a callable read and not only an effect because a vote has to move the number WITHOUT
  // touching the wall: re-listing page 0 is what the dialog used to trigger, and that threw away
  // every page "Load more" had appended — a visitor who had walked to 200 tiles found 50 and a
  // meaningless scroll position after a single up-vote.
  const loadScores = useCallback(() => {
    const onScreen = showFavourites ? (favourites ?? []) : memes.map((m) => m.id);
    void memeScores(onScreen)
      .then((fresh) => setScores((known) => new Map([...known, ...fresh])))
      // scores that never arrive stay UNKNOWN ("▲ n/a") — that is the designed degradation, and
      // it must not become an unhandled rejection either
      .catch(() => {});
  }, [memes, favourites, showFavourites]);
  useEffect(loadScores, [loadScores]);

  /**
   * The NSFW flag for the favourites on screen, asked for one meme at a time.
   *
   * The favourites wall hydrates opaque ids from user-collections, so it cannot learn the flag the
   * way the wall does (the listing carries it per meme). It used to look the id up in the wall
   * page it happened to have loaded — which holds 50 tiles and is tag-filtered whenever a filter
   * is on — so any favourite outside that set came back `undefined` and rendered in the clear:
   * a meme the moderators had blurred, unblurred on the very wall somebody curated.
   *
   * A flag that has not arrived yet is NOT a flag that says "safe", so the tile stays blurred
   * until this answers (see the call site).
   */
  useEffect(() => {
    if (!showFavourites || favourites === null) return;
    let live = true;
    void Promise.all(favourites.map((id) => memeMeta(id, token)
      .then((m) => [id, m.nsfw === true] as const)
      .catch(() => null)))
      .then((read) => {
        if (!live) return;
        setFavouriteNsfw((known) => new Map([
          ...known, ...read.filter((entry): entry is readonly [string, boolean] => entry !== null),
        ]));
      });
    return () => { live = false; };
  }, [showFavourites, favourites, token]);

  const showMore = () => {
    // the guard is not cosmetic: two clicks before the first answer lands would append the same
    // page twice, and duplicate tiles mean duplicate React keys
    if (loadingMore) return;
    setLoadingMore(true);
    void listMemes(tagFilter ?? undefined, pagesShown)
      .then((page) => {
        setMemes((current) => [...current, ...page]);
        setPagesShown((shown) => shown + 1);
        setMoreToShow(page.length === GALLERY_PAGE_SIZE);
      })
      .catch(() => setWarning('Could not load more memes — try again.'))
      .finally(() => setLoadingMore(false));
  };

  const loadFavourites = useCallback((t: string) => {
    void listFavourites(t)
      .then((refs) => {
        setFavourites(refs.filter((r) => r.itemType === 'meme').map((r) => r.itemId));
        setFavouritesError(false);
      })
      // the list stays whatever it was and the FAILURE is recorded separately: a 5xx from
      // user-collections must not read as "you have no favourites", and it must not take the
      // button that leads back to the wall with it
      .catch(() => setFavouritesError(true));
  }, []);

  // The one place that knows what a 401 means, wired to the one place that holds the token.
  // `renewed` lands the traded-in token where every later call will pick it up; `expired` says the
  // word out loud, because four of the most-used interactions in this UI used to answer an expired
  // session with absolute silence while the chip still read "signed in as …".
  useEffect(() => bindSession({
    renewed: setToken,
    expired: () => {
      // the cookie may still be alive (a refresh can fail on a hiccup) — kill the family too
      logout();
      setToken(null);
      setWarning('Your session expired — sign in again to vote or comment.');
    },
    // the token is fine and the session is untouched — the service would not take it, and the
    // write did not happen. Nobody downstream says this: every caller reads a 401 as "the session
    // died and this warning already went out", so without it the click vanished in silence
    refused: () => setWarning('That did not go through — your sign-in could not be confirmed just '
      + 'now. You are still signed in; try again in a moment.'),
  }), []);

  useEffect(() => {
    if (!token) {
      setUser('');
      setIsModerator(false);
      setIsAdmin(false);
      setFavourites(null);
      setFavouritesError(false);
      setShowFavourites(false);
      localStorage.removeItem('accessToken');
      return;
    }
    localStorage.setItem('accessToken', token);
    // through `request`, so a tab reopened after an hour trades its stale token for a fresh one
    // instead of throwing the visitor back at the sign-in panel with a live session in the cookie
    void request(`${SECURITY}/me`, { headers: authHeader(token) })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error('expired'))))
      .then((me: { email: string; roles?: string[] }) => {
        setUser(me.email);
        const roles = me.roles ?? [];
        setIsModerator(roles.includes('MODERATOR') || roles.includes('ADMIN'));
        setIsAdmin(roles.includes('ADMIN'));
      })
      .catch(() => setToken(null));
    loadFavourites(token);
  }, [token, loadFavourites]);

  const requireSignIn = () => setWarning('Sign in first — browsing is public, contributing is not.');

  // one call in flight per meme: a double click used to send a PUT and a DELETE for the same id and
  // then let whichever answered last decide what the star looks like
  const [starsInFlight, setStarsInFlight] = useState<ReadonlySet<string>>(new Set());

  const toggleFavourite = async (memeId: string) => {
    if (!token || favourites === null || starsInFlight.has(memeId)) return;
    const isFavourite = favourites.includes(memeId);
    const add = (list: string[]) => (list.includes(memeId) ? list : [memeId, ...list]);
    const drop = (list: string[]) => list.filter((id) => id !== memeId);
    // optimistic — the star answers instantly; a failed call rolls back with a notice.
    // FUNCTIONAL, never a snapshot: `setFavourites(favourites)` on failure restored the list as it
    // was at click time and wiped every star saved in between, leaving the screen showing a set
    // the server never had.
    setFavourites((current) => (current === null ? current
      : isFavourite ? drop(current) : add(current)));
    setStarsInFlight((current) => new Set(current).add(memeId));
    // undo THIS id and nothing else
    const rollBack = () => {
      setFavourites((current) => (current === null ? current
        : isFavourite ? add(current) : drop(current)));
      setWarning('The favourites service did not answer — try again.');
    };
    try {
      const ok = isFavourite ? await removeFavourite(memeId, token)
                             : await saveFavourite(memeId, token);
      if (!ok) rollBack();
    } catch {
      // A REFUSAL and a CALL THAT NEVER LANDED are the same thing to the star on the screen: the
      // optimistic flip has to come back either way. Only the refusal was handled, so a collections
      // service that was down left the star showing a favourite the server had never been told
      // about — and it stayed wrong until a reload.
      rollBack();
    } finally {
      setStarsInFlight((current) => {
        const next = new Set(current);
        next.delete(memeId);
        return next;
      });
    }
  };

  const upload = async (file: File) => {
    const body = new FormData();
    body.append('file', file);
    try {
      const r = await request('/memes', { method: 'POST', headers: authHeader(token), body });
      if (r.status !== 201) {
        setWarning(uploadRefusal(r.status, await r.json().catch(() => ({}))));
      }
    } catch {
      // without this the picture simply never appeared and nothing was said: an upload is the one
      // action here the user cannot repeat by guessing, so it has to name its own failure
      setWarning('The upload did not reach the gallery — check your connection and try again.');
    }
    refresh();
  };

  const star = (memeId: string) => (
    <IconButton
      size="small"
      aria-label={favourites?.includes(memeId) ? 'unfavourite' : 'favourite'}
      onClick={() => void toggleFavourite(memeId)}
      sx={{ position: 'absolute', top: 2, left: 2, zIndex: 1,
            color: favourites?.includes(memeId) ? 'warning.main' : 'rgba(255,255,255,0.8)' }}
    >
      {favourites?.includes(memeId) ? <StarIcon /> : <StarBorderIcon />}
    </IconButton>
  );

  return (
    <>
      <AppBar position="sticky" color="default">
        <Toolbar variant="dense">
          <Typography variant="h6" sx={{ flex: 1 }}>memes</Typography>
          {/* stays put when the favourites service is unwell (favouritesError) — it is the only
              navigation in this app, and hiding it left the visitor with F5 as the way back */}
          {token && (favourites !== null || favouritesError) && (
            <Button
              startIcon={showFavourites ? <StarIcon /> : <StarBorderIcon />}
              color={showFavourites ? 'warning' : 'inherit'}
              onClick={() => {
                if (!showFavourites && token) loadFavourites(token);
                setShowFavourites(!showFavourites);
              }}
              sx={{ mr: 1 }}
            >
              {showFavourites ? 'Back to the wall' : 'Favourites'}
            </Button>
          )}
          {isAdmin && (
            <Button startIcon={<SettingsIcon />} onClick={() => setAdminOpen(true)} sx={{ mr: 1 }}>
              Admin
            </Button>
          )}
          <Button component="label" variant="contained" startIcon={<UploadIcon />}
                  onClick={(e) => { if (!token) { e.preventDefault(); requireSignIn(); } }}>
            Upload
            <input type="file" accept="image/*" hidden
                   onChange={(e) => {
                     const file = e.target.files?.[0];
                     if (file) void upload(file);
                     e.target.value = '';
                   }} />
          </Button>
        </Toolbar>
      </AppBar>

      <Container maxWidth="md" sx={{ py: 2 }}>
        <Stack spacing={2}>
          {/* sign-out ends the session server-side too — the refresh cookie outlives the token
              by ~a day, and dropping only the token left it valid for the next person */}
          <AuthPanel token={token} user={user} onToken={setToken}
                     onLogout={() => { logout(); setToken(null); }} />

          {tagFilter && !showFavourites && (
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Typography variant="body2" color="text.secondary">Filtered by tag:</Typography>
              <Chip label={`#${tagFilter}`} color="primary" onDelete={() => setTagFilter(null)} />
            </Stack>
          )}

          {showFavourites ? (
            <>
              {/* "we could not find out" first, and it is NOT the empty-collection sentence:
                  telling somebody their favourites are gone when the service merely restarted is
                  a lie that reads like data loss */}
              {favouritesError && (
                <Alert
                  severity="warning"
                  action={<Button color="inherit" size="small"
                                  onClick={() => { if (token) loadFavourites(token); }}>Retry</Button>}
                >
                  Could not reach the favourites service, so this list may be incomplete — nothing
                  has been lost.
                </Alert>
              )}
              <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))' }}>
                {(favourites ?? []).map((id) => (
                  // `?? true`: until the flag has been read this tile blurs. An unread flag is not
                  // a meme anybody vouched for, and showing a flagged one in the clear is the
                  // failure that matters here — a blur that lifts a moment later is not
                  <FavouriteTile key={id} memeId={id} nsfw={favouriteNsfw.get(id) ?? true}
                                 score={scores.get(id)} star={star(id)} onOpen={() => setSelected(id)} />
                ))}
              </Box>
              {(favourites ?? []).length === 0 && !favouritesError && (
                <Typography color="text.secondary">
                  No favourites yet — star a meme on the wall and it lands here.
                </Typography>
              )}
            </>
          ) : (
            <>
              {wallError && (
                <Alert
                  severity="warning"
                  action={<Button color="inherit" size="small" onClick={refresh}>Retry</Button>}
                >
                  The gallery did not answer — this is a service problem, not an empty wall.
                </Alert>
              )}
              <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))' }}>
                {memes.map((m) => (
                  <Card key={m.id} sx={{ position: 'relative' }}>
                    <CardActionArea onClick={() => setSelected(m.id)}>
                      <CardMedia component="img" image={`/memes/${m.id}/thumbnail`} loading="lazy"
                                 sx={{ aspectRatio: '1', objectFit: 'cover',
                                       // NSFW: the moderators' flag blurs the tile; opening the
                                       // dialog is the deliberate act that reveals the image
                                       ...(m.nsfw ? { filter: 'blur(14px)' } : {}) }} />
                      {m.nsfw && (
                        <Chip label="NSFW" size="small" color="warning"
                              sx={{ position: 'absolute', top: 6, right: 6 }} />
                      )}
                      <ScoreChip score={scores.get(m.id)} />
                    </CardActionArea>
                    {token && favourites !== null && star(m.id)}
                  </Card>
                ))}
              </Box>
              {moreToShow && (
                <Button onClick={showMore} disabled={loadingMore} sx={{ alignSelf: 'center' }}>
                  Load more
                </Button>
              )}
              {memes.length === 0 && !wallError && (
                <Typography color="text.secondary">
                  {tagFilter ? `No memes tagged #${tagFilter}.` : 'No memes yet — sign in and upload the first one.'}
                </Typography>
              )}
            </>
          )}
        </Stack>
      </Container>

      {selected && (
        <MemeDialog memeId={selected} token={token} isModerator={isModerator}
                    onVoted={loadScores} onRequireSignIn={requireSignIn}
                    onNsfwChanged={(flagged) => {
                      // the moderator's own wall must show what they just decided: the tile blurs
                      // from this array, and nothing was refreshing it until something unrelated
                      // happened to re-list the wall
                      setMemes((current) => current.map((m) =>
                        (m.id === selected ? { ...m, nsfw: flagged } : m)));
                      setFavouriteNsfw((known) => new Map(known).set(selected, flagged));
                    }}
                    onTagClick={(t) => { setTagFilter(t); setSelected(null); }}
                    onDeleted={() => { setSelected(null); refresh(); }}
                    onClose={() => setSelected(null)} />
      )}

      <AdminPanel token={token} open={adminOpen} onClose={() => setAdminOpen(false)} />

      <Snackbar open={warning !== null} autoHideDuration={4000} onClose={() => setWarning(null)}>
        <Alert severity="warning" onClose={() => setWarning(null)}>{warning}</Alert>
      </Snackbar>
    </>
  );
}

/**
 * The vote count on a tile — and the one place that decides what "no number" looks like.
 *
 * `undefined` is NOT zero. It means this UI has no tally for the meme: the gallery did not answer
 * for that id (a favourite whose meme is gone), or the score call failed. Then the chip says "n/a",
 * the same word the dialog uses for a null tally — because "▲ 0" under a meme is a statement, and
 * "nobody voted for this" is a statement we would be making up. A meme that really has no votes
 * comes back from the server as a 0 and gets the confident "▲ 0" it has earned.
 */
function ScoreChip({ score }: { score: number | undefined }) {
  const known = score !== undefined;
  return (
    <Chip
      data-testid="tile-score"
      label={known ? `▲ ${score}` : '▲ n/a'}
      aria-label={known ? `score ${score}` : 'score unknown'}
      size="small"
      sx={{ position: 'absolute', bottom: 6, left: 6, ...(known ? {} : { opacity: 0.6 }) }}
    />
  );
}

/**
 * A tile on the favourites wall: a hydrated ref. The ref outlives its meme — collections stores
 * opaque ids and never checks back — so a thumbnail that no longer loads renders as an
 * "unavailable" keepsake, with the star still there to let it go.
 *
 * <p>That state is TRANSIENT: MEME_DELETED reaches user-collections seconds later and the ref is
 * swept, which is why the browser suite cannot assert it without racing the broker (it tried, and
 * spent its life red — see e2e/steps/favourites.steps.mjs). Exported for the unit suite, which can
 * hold the moment still: App.test.tsx fires the image's own onError and looks.
 */
export function FavouriteTile({ memeId, nsfw, score, star, onOpen }: {
  memeId: string;
  nsfw: boolean | undefined;
  score: number | undefined;
  star: JSX.Element;
  onOpen: () => void;
}) {
  const [gone, setGone] = useState(false);
  return (
    <Card sx={{ position: 'relative' }}>
      {gone ? (
        <Box sx={{ aspectRatio: '1', display: 'flex', alignItems: 'center', justifyContent: 'center',
                   bgcolor: 'action.hover' }}>
          <Typography variant="body2" color="text.secondary">unavailable</Typography>
        </Box>
      ) : (
        <CardActionArea onClick={onOpen}>
          {/* the wall's thumbnail may sit in the browser cache long after the meme is gone, so the
              request says WHO is asking: MemeController answers ?wall=favourites with no-store,
              which is what forces a real answer — and a 404 turns the tile into a keepsake */}
          <CardMedia component="img" image={`/memes/${memeId}/thumbnail?wall=favourites`} loading="lazy"
                     onError={() => setGone(true)}
                     sx={{ aspectRatio: '1', objectFit: 'cover',
                           ...(nsfw ? { filter: 'blur(14px)' } : {}) }} />
          {nsfw && (
            <Chip label="NSFW" size="small" color="warning"
                  sx={{ position: 'absolute', top: 6, right: 6 }} />
          )}
          <ScoreChip score={score} />
        </CardActionArea>
      )}
      {star}
    </Card>
  );
}
