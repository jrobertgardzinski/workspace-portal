package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeRepository;

import java.util.Optional;

/**
 * Serves a meme's image in the best format the caller accepts: WebP when they asked for it and the
 * encoder can produce it (cached in the {@link ObjectStore} under a per-meme WebP key, so it is
 * encoded once), otherwise the stored PNG. The decision — negotiate, cache, fall back — lives here,
 * not in the web boundary.
 */
public class ServeMeme {

    // JDK System.Logger, not SLF4J — this module is deliberately framework-free
    private static final System.Logger LOG = System.getLogger(ServeMeme.class.getName());

    /** The bytes to serve and the content type they carry. */
    public record Image(byte[] data, String contentType) {}

    private final MemeRepository memes;
    private final ObjectStore objects;
    private final ImageEncoder encoder;

    public ServeMeme(MemeRepository memes, ObjectStore objects, ImageEncoder encoder) {
        this.memes = memes;
        this.objects = objects;
        this.encoder = encoder;
    }

    public Optional<Image> execute(String memeId, boolean wantsWebp) {
        // The gate is exists(), not find() — the same order MakeThumbnail spells out: the cheap,
        // indexed row question first, the expensive remote blob only when the RESPONSE needs it.
        // With find() as the gate, every WebP cache hit still pulled the full PNG out of
        // MinIO/S3 and threw it away.
        if (!memes.exists(memeId)) {
            return Optional.empty();
        }
        String webpKey = memeId + ".webp";
        if (wantsWebp) {
            Optional<byte[]> cached = objects.get(webpKey);
            if (cached.isPresent()) {
                return Optional.of(new Image(cached.get(), "image/webp"));
            }
        }
        Optional<Meme> meme = memes.find(memeId);   // now, and only now, the bytes
        if (meme.isEmpty()) {
            // The row is there but the active store holds no object for it. 404 is the honest
            // answer to "give me this picture" — there is no picture to give. Everything that is
            // NOT the picture (metadata, votes, tags, deletion) is answered from the row and keeps
            // working, so such a meme can still be moderated and taken down.
            return Optional.empty();
        }
        byte[] png = meme.get().data();
        if (!wantsWebp) {
            return Optional.of(new Image(png, "image/png"));
        }
        return encoder.toWebp(png)
                .map(webp -> {
                    cacheBestEffort(memeId, webpKey, webp);    // encode once, then serve from cache
                    return new Image(webp, "image/webp");
                })
                .or(() -> Optional.of(new Image(png, "image/png")));   // encoder down: PNG still works
    }

    /**
     * The cache write is an optimisation, never a condition: we already hold the encoded WebP the
     * caller asked for, so a store hiccup (disk full, S3 blip) must cost the NEXT request a
     * re-encode, not this request its response.
     */
    private void cacheBestEffort(String memeId, String key, byte[] webp) {
        try {
            objects.put(key, webp);
            // Guard against the delete race: between our find() above and this put, the meme may
            // have been deleted — a variant written NOW would otherwise be orphaned forever (no
            // later delete will come for it). The deleter sweeps the variant once inside its DB
            // transaction and once more after its commit (JdbcMemeRepository.deleteById; on
            // filesystem/S3 stores the in-transaction sweep is itself deferred to after-commit),
            // so together with this re-check every interleaving is covered: a put that lands
            // before the delete's commit is taken along by the deleter's after-commit sweep; a
            // put that lands after it is caught HERE — exists() then sees the row gone and we
            // remove what we just wrote.
            if (!memes.exists(memeId)) {
                objects.delete(key);
                LOG.log(System.Logger.Level.INFO,
                        "meme " + memeId + " was deleted while its WebP was being encoded — removed the freshly cached " + key);
            }
        } catch (RuntimeException cacheMiss) {
            LOG.log(System.Logger.Level.WARNING,
                    "could not cache " + key + " — serving the encoded bytes anyway", cacheMiss);
        }
    }
}
