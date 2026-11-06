package com.jrobertgardzinski.memes.tags;

import java.util.Locale;

/**
 * A meme tag: the folksonomy's atom. Normalised on construction — lowercase, trimmed — so
 * "Cats", " cats " and "cats" are the same tag; legal tags are 2..30 characters of latin
 * letters, digits and single dashes ("monday-mood"). Anything else is refused loudly: a tag is
 * part of a URL and a search key, not free text.
 */
public record Tag(String value) {

    private static final String LEGAL = "[a-z0-9]+(-[a-z0-9]+)*";
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 30;

    public Tag {
        if (value == null || !value.matches(LEGAL)
                || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a tag is 2..30 lowercase letters, digits and single dashes: '" + value + "'");
        }
    }

    /** Normalise raw user input into a tag (or throw when nothing legal remains). */
    public static Tag of(String raw) {
        return new Tag(raw == null ? null : raw.trim().toLowerCase(Locale.ROOT));
    }
}
