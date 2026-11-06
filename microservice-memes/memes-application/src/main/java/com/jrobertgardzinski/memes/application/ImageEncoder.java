package com.jrobertgardzinski.memes.application;

import java.util.Optional;

/**
 * Port to the image-encoder service: turn a stored PNG into a smaller WebP. Optional by design —
 * an empty result (service disabled, down, or the image unreadable) means "serve the PNG you
 * already have", so a failure degrades quality, never availability.
 */
public interface ImageEncoder {

    Optional<byte[]> toWebp(byte[] png);
}
