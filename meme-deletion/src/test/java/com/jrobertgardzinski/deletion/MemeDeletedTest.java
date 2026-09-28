package com.jrobertgardzinski.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemeDeletedTest {

    private static final String MEME = "8f1c1a3e-2b4d-4c5e-9a7f-1d2e3b4c5a6d";

    @Test
    @DisplayName("a deletion that names a meme is an event")
    void a_usable_id_parses() {
        assertEquals(Optional.of(new MemeDeleted(MEME)), MemeDeleted.of(MEME));
    }

    @Test
    @DisplayName("a deletion that names nothing is not an event: missing, blank, or not a uuid")
    void the_three_unusable_shapes() {
        assertEquals(Optional.empty(), MemeDeleted.of(null));
        assertEquals(Optional.empty(), MemeDeleted.of(""));
        assertEquals(Optional.empty(), MemeDeleted.of("   "));
        assertEquals(Optional.empty(), MemeDeleted.of("not-an-id"));
    }

    @Test
    @DisplayName("a uuid of the right length but the wrong shape is not an id")
    void a_lax_uuid_is_not_an_id() {
        // UUID.fromString accepts 1-1-1-1-1; the length check is what rejects it, and a
        // 36-character string that is not hexadecimal is what the length check alone would miss
        assertEquals(Optional.empty(), MemeDeleted.of("1-1-1-1-1"));
        assertEquals(Optional.empty(), MemeDeleted.of("zzzzzzzz-2b4d-4c5e-9a7f-1d2e3b4c5a6d"));
    }

    @Test
    @DisplayName("the message names its type and its meme, and nothing else")
    void the_wire_shape() {
        assertEquals(Map.of("type", "MEME_DELETED", "memeId", MEME), MemeDeleted.of(MEME).orElseThrow().fields());
    }
}
