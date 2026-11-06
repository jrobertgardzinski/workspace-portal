package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.config.ThumbnailSize;
import com.jrobertgardzinski.memes.image.InvalidImageException;
import com.jrobertgardzinski.memes.image.OptimizedImage;
import com.jrobertgardzinski.memes.image.WebImageOptimizer;

import java.util.Optional;

/**
 * Makes a thumbnail (small PNG) of a stored meme on demand. Empty if there is no such meme.
 *
 * <p>Cached in the {@link ObjectStore} under {@code {id}.thumb}, the same pattern as ServeMeme's
 * WebP variant: the FIRST request decodes the full image (under the infrastructure's decode
 * semaphore — the expensive, permit-holding step) and caches the result; every later request is
 * served straight from the store — no decode, no permit, just one indexed existence check on the
 * meme row. Before this, EVERY thumbnail request decoded the full image, so a burst of gallery
 * scrolls could saturate the semaphore and feed readers 429s. A thumbnail is immutable per id
 * (ids never return to circulation), so the cache needs no invalidation beyond the delete sweep
 * in the repository adapter — but it does need that existence check, because a crash can strand
 * a variant no sweep will ever reach (see {@link #execute}).
 */
public class MakeThumbnail {

    // JDK System.Logger, not SLF4J — this module is deliberately framework-free
    private static final System.Logger LOG = System.getLogger(MakeThumbnail.class.getName());

    private final MemeRepository memeRepository;
    private final ObjectStore objects;
    private final WebImageOptimizer optimizer;
    private final ThumbnailSize thumbnailSize;

    public MakeThumbnail(MemeRepository memeRepository, ObjectStore objects,
                         WebImageOptimizer optimizer, ThumbnailSize thumbnailSize) {
        this.memeRepository = memeRepository;
        this.objects = objects;
        this.optimizer = optimizer;
        this.thumbnailSize = thumbnailSize;
    }

    public Optional<byte[]> execute(String memeId) {
        String thumbKey = memeId + ".thumb";
        // A cache hit is served only for a meme that still EXISTS, so the row is asked FIRST.
        // The sweeps (in-transaction + after-commit in JdbcMemeRepository) and the post-put
        // re-check below cover every interleaving in which both parties survive — but not a
        // CRASH, and the window they leave open is the ugly kind: a JVM that dies between the
        // put and the re-check leaves a .thumb of a meme that is gone, no later delete will
        // ever come for it, and a cache-only hit would keep serving a deleted person's picture
        // forever. (ServeMeme's .webp variant cannot be SERVED that way either — it now asks the
        // same exists() gate before touching the store — but it is not swept: no request for a
        // deleted meme's WebP goes past that gate, so nothing there notices the orphan.)
        //
        // The gate is exists(), not find(): one indexed "SELECT 1 FROM memes WHERE id = ?" that
        // loads no blob. Measured on the DB store (H2, warm): ~21us for the exists, ~12us for the
        // blob read it joins, ~609us for the whole served cache hit — the gate costs about 3.5%
        // of a hit it makes safe. At that price tightness beats statistics: a sampled check
        // (every Nth hit) would keep serving the orphan in between, and a periodic sweep would
        // have to enumerate the store to find one. Ids are never reissued, so a hit that passes
        // this gate can never be a DIFFERENT meme's thumbnail.
        //
        // ORDER, deliberately exists() -> get() and not the other way round: with the store asked
        // first, an orphan (and every request for an id that is simply gone) paid a full blob
        // read from S3/MinIO for bytes that were then thrown away. The cheap, local question
        // decides first; the expensive, remote one is asked only for a meme that is really there.
        if (!memeRepository.exists(memeId)) {
            sweepBestEffort(thumbKey);
            return Optional.empty();
        }
        Optional<byte[]> cached = objects.get(thumbKey);
        if (cached.isPresent()) {
            return cached;
        }
        return memeRepository.find(memeId)
                .map(meme -> decodeStored(memeId, meme.data()))
                .map(OptimizedImage::data)
                .map(thumb -> {
                    cacheBestEffort(memeId, thumbKey, thumb);   // decode once, then serve from cache
                    return thumb;
                });
    }

    /**
     * Self-healing for the crash window: whatever hides under {@code {id}.thumb} of a meme that
     * no longer exists is an orphan no other delete will ever come for, so it goes now — the
     * next request pays a plain miss and the store stops carrying a deleted meme's derived image.
     *
     * <p>BEST-EFFORT, like every other write to the {@link ObjectStore} on a read path (see
     * {@link #cacheBestEffort}). In production the store is S3/MinIO and its delete can throw
     * {@code SdkException} — an unchecked exception that used to ride straight out of the use
     * case, through the controller, and answer a MinIO hiccup with a 500. The honest answer to
     * "give me the thumbnail of a meme that does not exist" is 404, whether or not the cleanup
     * behind it succeeded; a surviving orphan is a wasted object, not a wrong answer, and the
     * next request will try to sweep it again.
     *
     * <p>The delete is unconditional because the store has no cheap "is there anything there?" —
     * asking would mean the full blob read this order exists to avoid. A delete of a key that is
     * not there is a no-op in both adapters (a DELETE ... WHERE key = ? that matches no row, a
     * 204 from S3), so the cost of the blind sweep is one round trip on a 404, and it buys the
     * orphan case a sweep that costs no blob transfer at all. That trade is also why this logs at
     * DEBUG: after the reorder a sweep can no longer tell an orphan apart from a request for an
     * id that never existed, so the line stopped being evidence of a crash and would otherwise
     * shout on every 404.
     */
    private void sweepBestEffort(String thumbKey) {
        try {
            objects.delete(thumbKey);
        } catch (RuntimeException storeHiccup) {
            LOG.log(System.Logger.Level.WARNING, "could not sweep " + thumbKey
                    + " — the meme is gone, the answer is still 404; a leftover object may remain",
                    storeHiccup);
            return;
        }
        LOG.log(System.Logger.Level.DEBUG, "swept " + thumbKey
                + " — no such meme, so nothing under that key may be served");
    }

    /**
     * These are STORED bytes, already validated by the optimizer at upload — if they no longer
     * decode, the server's data went bad, not the caller's request. InvalidImageException would
     * ride the web boundary's "bad upload" mapping down to a 400 blaming the caller; re-signal it
     * as the server fault it is (WebErrorHandler's floor answers a 500 with a generic body).
     */
    private OptimizedImage decodeStored(String memeId, byte[] stored) {
        try {
            return optimizer.toPngWithin(stored, thumbnailSize.maxDimension());
        } catch (InvalidImageException corruptedInStore) {
            throw new IllegalStateException(
                    "stored image no longer decodes — data corruption for meme " + memeId,
                    corruptedInStore);
        }
    }

    /**
     * The cache write is an optimisation, never a condition — a store hiccup must cost the NEXT
     * request a re-decode, not this request its response. The post-put {@code exists} re-check
     * mirrors ServeMeme's WebP guard against the delete race: a variant written after the
     * delete's sweeps would otherwise be orphaned forever (no later delete will come for it);
     * together with the deleter's in-transaction and after-commit sweeps every interleaving is
     * covered.
     */
    private void cacheBestEffort(String memeId, String key, byte[] thumb) {
        try {
            objects.put(key, thumb);
            if (!memeRepository.exists(memeId)) {
                objects.delete(key);
                LOG.log(System.Logger.Level.INFO,
                        "meme " + memeId + " was deleted while its thumbnail was being made — removed the freshly cached " + key);
            }
        } catch (RuntimeException cacheMiss) {
            LOG.log(System.Logger.Level.WARNING,
                    "could not cache " + key + " — serving the decoded thumbnail anyway", cacheMiss);
        }
    }
}
