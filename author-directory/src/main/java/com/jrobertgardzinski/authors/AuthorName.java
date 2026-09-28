package com.jrobertgardzinski.authors;

/** How a person is shown next to their content. Computed by security, never the address. */
public record AuthorName(String display) {

    public AuthorName {
        if (display == null || display.isBlank()) {
            throw new IllegalArgumentException("an author name needs a display form");
        }
    }
}
