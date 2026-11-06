package com.jrobertgardzinski.memes.infrastructure;

/**
 * All {@link ConcurrencyGuardedImageOptimizer} decode permits stayed taken for the whole wait:
 * the service is decoding as many images as it is willing to hold in heap at once. Not the
 * caller's fault and not a server fault either — a "later, please", which {@link WebErrorHandler}
 * turns into 429 + Retry-After, the same refusal shape as the per-user upload rate limit.
 */
class ImageDecodeOverloadedException extends RuntimeException {

    ImageDecodeOverloadedException(String message) {
        super(message);
    }
}
