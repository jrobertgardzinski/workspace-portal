package com.jrobertgardzinski.memes.config;

/**
 * Configuration for image optimisation: the largest dimension (width or height, in pixels) a stored
 * meme may have. Uploads bigger than this are scaled down. Must be positive.
 */
public record ImageLimits(int maxDimension) {

    public ImageLimits {
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("maxDimension must be positive, was " + maxDimension);
        }
    }
}
