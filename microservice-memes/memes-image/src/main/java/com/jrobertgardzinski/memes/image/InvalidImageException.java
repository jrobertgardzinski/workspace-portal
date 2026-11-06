package com.jrobertgardzinski.memes.image;

/**
 * The caller's image was refused: unreadable or truncated bytes, a format ImageIO cannot read, or
 * declared dimensions beyond the decode guard. Extends {@link IllegalArgumentException} so
 * framework-free callers keep seeing plain "bad input", but gives the web boundary a PRECISE type
 * to map to 400 — a bare {@link java.io.UncheckedIOException} from anywhere else (a blob store
 * dying mid-read on a GET) is a server fault and must ride the 500 rail instead. Messages are
 * written for the uploader and safe to return verbatim.
 */
public class InvalidImageException extends IllegalArgumentException {

    public InvalidImageException(String message) {
        super(message);
    }

    public InvalidImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
