package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.image.OptimizedImage;
import com.jrobertgardzinski.memes.image.WebImageOptimizer;

import com.jrobertgardzinski.identity.UserId;

import java.util.Optional;
import java.util.UUID;

/**
 * Publishes a meme from a raw upload: optimises the image to a browser-friendly format, then
 * claims the content atomically — if that exact image is already stored (or a simultaneous upload
 * beat us to it), the existing meme's id is returned and nothing new is stored; otherwise the new
 * meme is saved under the claimed id.
 */
public class PublishMeme {

    // JDK System.Logger, not SLF4J — this module is deliberately framework-free
    private static final System.Logger LOG = System.getLogger(PublishMeme.class.getName());

    private final WebImageOptimizer optimizer;
    private final MemeRepository repository;
    private final MemeContentIndex contentIndex;
    private final ObjectStore objects;

    public PublishMeme(WebImageOptimizer optimizer, MemeRepository repository, MemeContentIndex contentIndex,
                       ObjectStore objects) {
        this.optimizer = optimizer;
        this.repository = repository;
        this.contentIndex = contentIndex;
        this.objects = objects;
    }

    /**
     * The uploader is an id. A token without one predates the cutover and is nobody here, so the
     * gate refuses it before this is ever called.
     */
    public String execute(byte[] rawImage, UserId author) {
        OptimizedImage optimized = optimizer.optimize(rawImage);
        String candidate = UUID.randomUUID().toString();
        String owner = contentIndex.claim(optimized.data(), candidate);
        if (!owner.equals(candidate)) {
            return owner;               // the picture is already up — nothing new is stored
        }
        try {
            repository.save(new Meme(candidate, author, optimized.format(), optimized.data()));
        } catch (RuntimeException saveFailed) {
            // compensate the won claim, or the hash stays owned by a meme that never got stored and
            // every future upload of this picture dedups into a ghost. A shared transaction cannot
            // cover this — the repository may write bytes to S3/filesystem, beyond any DB commit.
            // Best-effort like every compensation: if the remove ALSO fails (the same dead DB,
            // usually), it must neither mask why the publish failed nor stop the blob cleanup
            // below — log it, pin it to the original as suppressed, move on.
            try {
                contentIndex.remove(candidate);
            } catch (RuntimeException removeFailed) {
                LOG.log(System.Logger.Level.WARNING,
                        "publish of " + candidate + " failed and its content claim could not be"
                                + " released — identical re-uploads will dedup into a ghost", removeFailed);
                saveFailed.addSuppressed(removeFailed);
            }
            // ... and the uploaded bytes, for the same reason: on S3/filesystem the repository's
            // objects.put happens inside the DB transaction but outside its reach — a rollback (or
            // a commit that fails AFTER the bytes went up) takes the row back and leaves the blob
            // orphaned under an id nothing will ever reference. By the time the exception reaches
            // us the transaction is over, so this delete runs immediately, not parked after-commit;
            // on a never-written key every adapter's delete is a no-op, so compensating is safe
            // even when the failure struck before the upload.
            deleteUploadedBytesBestEffort(candidate);
            throw saveFailed;
        }
        return candidate;
    }

    /** Best-effort: a failed cleanup is logged, never allowed to mask why the publish failed. */
    private void deleteUploadedBytesBestEffort(String memeId) {
        try {
            objects.delete(memeId);
        } catch (RuntimeException cleanupFailed) {
            LOG.log(System.Logger.Level.WARNING,
                    "publish of " + memeId + " failed and its uploaded bytes could not be removed"
                            + " — orphaned blob left in the object store", cleanupFailed);
        }
    }
}
