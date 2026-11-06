package com.jrobertgardzinski.memes.infrastructure;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Caps how many uploads may be HOLDING RAW BYTES at once — a different quantity from the one
 * {@link ConcurrencyGuardedImageOptimizer} bounds, and the reason there are two semaphores rather
 * than one.
 *
 * <p><strong>What went wrong.</strong> The order on {@code POST /memes} was: authenticate, let the
 * DispatcherServlet parse the multipart (to disk — {@code fileSizeThreshold=0} — so the heap paid
 * nothing yet), check the per-user rate limit, call {@code file.getBytes()} — and only THEN, deep
 * inside {@code publishMeme.execute}, reach the decode semaphore. That fourth step allocates up to
 * {@code spring.servlet.multipart.max-file-size} of heap per request, and nothing bounded how many
 * requests could stand there at once: {@code server.tomcat.threads.max} was never set, so the
 * ceiling was Tomcat's default of 200. Two hundred requests holding ten megabytes each is 2000 MB
 * against a ~1075 MiB heap. The per-user rate limit does not help — it is a counter over a window,
 * so one account can have all twelve of its allowance in flight simultaneously.
 *
 * <p>The upload path is authenticated ({@link RequireSignInFilter} refuses before the multipart is
 * parsed), so this is abuse by signed-in users rather than an anonymous flood. It is still the
 * cheapest way to take the service down from a laptop.
 *
 * <p><strong>Why not simply take a decode permit earlier.</strong> Because thumbnails take decode
 * permits too ({@code toPngWithin}), so one thread would end up holding the same semaphore twice
 * and deadlock itself at a concurrency of one. The two limits are also genuinely about different
 * things: this one is measured in megabytes of raw upload held, that one in megabytes of decoded
 * surface. Sizing them together is what makes the arithmetic in {@code k8s/base/memes.yaml}
 * checkable at all.
 */
final class UploadAdmission {

    private final Semaphore permits;
    private final Duration patience;

    UploadAdmission(int concurrency, Duration patience) {
        // fair: a burst of newer uploads must not starve one that has been waiting
        this.permits = new Semaphore(concurrency, true);
        this.patience = patience;
    }

    /**
     * Runs {@code upload} with an admission permit held, or refuses.
     *
     * @throws ImageDecodeOverloadedException  if no permit came free within the patience window —
     *                                         the same 429 + Retry-After the decode guard answers,
     *                                         because to a client the two are the same "come back
     *                                         in a moment"
     * @throws ImageDecodeInterruptedException on shutdown, as a 503: a 429 would invite the client
     *                                         to retry against the instance being torn down
     */
    <T> T admit(java.util.function.Supplier<T> upload) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(patience.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ImageDecodeInterruptedException("interrupted while waiting to start an upload");
        }
        if (!acquired) {
            throw new ImageDecodeOverloadedException(
                    "all upload slots stayed taken for " + patience.toMillis() + " ms");
        }
        try {
            return upload.get();
        } finally {
            permits.release();
        }
    }
}
