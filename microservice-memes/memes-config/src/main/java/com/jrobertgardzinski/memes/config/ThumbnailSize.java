package com.jrobertgardzinski.memes.config;

/**
 * Configuration for meme thumbnails: the largest dimension (px) a generated thumbnail may have.
 * Must be positive.
 */
public record ThumbnailSize(int maxDimension) {

    public ThumbnailSize {
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("maxDimension must be positive, was " + maxDimension);
        }
    }
}
