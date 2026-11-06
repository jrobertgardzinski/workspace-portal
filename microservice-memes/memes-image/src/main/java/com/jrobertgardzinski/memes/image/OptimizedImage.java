package com.jrobertgardzinski.memes.image;

/**
 * The result of optimising an uploaded image: the re-encoded bytes and their (browser-friendly)
 * format.
 */
public record OptimizedImage(byte[] data, String format) {
}
