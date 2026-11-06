package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.config.ImageLimits;
import com.jrobertgardzinski.memes.image.OptimizedImage;
import com.jrobertgardzinski.memes.image.WebImageOptimizer;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Caps how many image decodes run at once, JVM-wide. The optimizer's per-image bomb guard
 * (8000px per side) still admits ~256 MB of heap PER DECODE at 16-bit depth, and the upload
 * rate limit is per-user — so N users (or one user with N stolen tokens) could still stack
 * enough concurrent decodes to take the heap down. This decorator makes the residual bound
 * global: at most {@code memes.decode.concurrency} decodes in flight; a request that cannot
 * get a permit within the patience window is refused with
 * {@link ImageDecodeOverloadedException} (→ 429 + Retry-After) instead of queueing forever.
 *
 * <p>Lives in INFRASTRUCTURE by design: a JVM-wide semaphore is a deployment resource (its
 * size is an env dial, its refusal an HTTP status), so it wraps the pure {@code memes-image}
 * optimizer as a decorator wired in {@link MemesConfig} — no static state, and the pure module
 * stays free of concurrency policy. Extends rather than implements because the use cases
 * depend on the concrete {@link WebImageOptimizer}; every decode entry point (optimize for
 * uploads, toPngWithin for thumbnails) is overridden to pass through the same permits.
 */
class ConcurrencyGuardedImageOptimizer extends WebImageOptimizer {

    private final WebImageOptimizer delegate;
    private final Semaphore permits;
    private final Duration patience;

    ConcurrencyGuardedImageOptimizer(WebImageOptimizer delegate, ImageLimits limits,
                                     int concurrency, Duration patience) {
        super(limits);   // never used to decode — the delegate does that; the parent just wants its limits
        this.delegate = delegate;
        this.permits = new Semaphore(concurrency, true);   // fair: no request starves behind a stream of newer ones
        this.patience = patience;
    }

    @Override
    public OptimizedImage optimize(byte[] input) {
        return guarded(() -> delegate.optimize(input));
    }

    @Override
    public OptimizedImage toPngWithin(byte[] input, int maxDimension) {
        return guarded(() -> delegate.toPngWithin(input, maxDimension));
    }

    private OptimizedImage guarded(Supplier<OptimizedImage> decode) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(patience.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // restore the flag FIRST — whoever interrupted (shutdown, cancellation) must still
            // see it; then refuse as "unavailable" (503), NOT as overload (429): a 429 would
            // invite the client to retry against the very instance being torn down
            Thread.currentThread().interrupt();
            throw new ImageDecodeInterruptedException("interrupted while waiting for a decode permit");
        }
        if (!acquired) {
            throw new ImageDecodeOverloadedException(
                    "all decode permits stayed taken for " + patience.toMillis() + " ms");
        }
        try {
            return decode.get();
        } finally {
            permits.release();
        }
    }
}
