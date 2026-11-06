package com.jrobertgardzinski.memes.infrastructure;

/**
 * The wait for a {@link ConcurrencyGuardedImageOptimizer} decode permit was INTERRUPTED — not
 * timed out. An interrupt means someone is tearing this worker down (container shutdown, request
 * cancellation), which is neither the caller's fault nor overload: answering 429 like the
 * timeout would tell the client "you are being throttled, retry against me shortly" when this
 * instance is the one thing a retry should avoid. {@link WebErrorHandler} maps it to 503 —
 * "this instance is unavailable, go elsewhere". The guard re-sets the thread's interrupt flag
 * before throwing, so the shutdown that caused it still sees the interruption.
 */
class ImageDecodeInterruptedException extends RuntimeException {

    ImageDecodeInterruptedException(String message) {
        super(message);
    }
}
