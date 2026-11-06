import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { FavouriteTile } from './App';

/**
 * The favourites wall's keepsake state — the one the browser suite gave up on.
 *
 * e2e/features/favourites.feature used to assert this tile against a live stack and was permanently
 * red for it: the state exists only between the author's DELETE and MEME_DELETED reaching
 * user-collections, and the cascade closes that window in seconds. Here there is no cascade and no
 * broker, so the moment holds still and the assertion is ordinary.
 */
describe('a favourite whose meme is already gone', () => {
  const tile = (props: Partial<Parameters<typeof FavouriteTile>[0]> = {}) =>
    render(
      <FavouriteTile
        memeId="meme-1"
        nsfw={false}
        score={3}
        star={<button type="button">unfavourite</button>}
        onOpen={() => {}}
        {...props}
      />,
    );

  it('shows the thumbnail while the meme is still there', () => {
    const { container } = tile();

    expect(container.querySelector('img')).toBeInTheDocument();
    expect(screen.queryByText('unavailable')).not.toBeInTheDocument();
  });

  it('turns into an unavailable keepsake when the thumbnail 404s', () => {
    const { container } = tile();

    // the component learns the meme is gone from the image's OWN error event — there is no poll and
    // no second request, so this is the whole trigger, faithfully reproduced
    fireEvent.error(container.querySelector('img')!);

    expect(screen.getByText('unavailable')).toBeInTheDocument();
    expect(container.querySelector('img')).not.toBeInTheDocument();
  });

  it('keeps the star on the keepsake, so the dead ref can still be let go', () => {
    const { container } = tile();
    fireEvent.error(container.querySelector('img')!);

    // the point of rendering a keepsake at all: a tile with no picture and no star would strand the
    // ref in collections with nothing in the UI able to remove it
    expect(screen.getByRole('button', { name: 'unfavourite' })).toBeInTheDocument();
  });

  it('does not open the dialog once the tile is a keepsake', () => {
    const onOpen = vi.fn();
    const { container } = tile({ onOpen });
    fireEvent.error(container.querySelector('img')!);

    fireEvent.click(screen.getByText('unavailable'));

    // the clickable area goes away with the image; a meme that 404s has no dialog to show
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('marks its thumbnail request as the favourites wall, which is what makes it uncacheable', () => {
    const { container } = tile();

    // The query is not cache-busting by itself — it is a stable URL, so on its own it would only
    // buy a second cache entry. What it does is TELL THE SERVER who is asking, and MemeController
    // answers ?wall=favourites with no-store precisely so this tile has to put the question to the
    // server and can learn that the meme is gone. That half is pinned in ThumbnailCacheTest
    // (memes-infrastructure); this one pins the half the browser sends.
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      '/memes/meme-1/thumbnail?wall=favourites',
    );
  });
});
