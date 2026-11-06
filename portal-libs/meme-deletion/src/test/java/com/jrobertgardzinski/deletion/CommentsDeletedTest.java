package com.jrobertgardzinski.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommentsDeletedTest {

    private static final String MEME = "8f1c1a3e-2b4d-4c5e-9a7f-1d2e3b4c5a6d";
    private static final String ONE = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
    private static final String TWO = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e";

    @Test
    @DisplayName("an announcement whose meme is not an id is not an event")
    void the_meme_id_is_the_event() {
        assertEquals(Optional.empty(), CommentsDeleted.of(null, List.of(ONE)));
        assertEquals(Optional.empty(), CommentsDeleted.of("", List.of(ONE)));
        assertEquals(Optional.empty(), CommentsDeleted.of("not-an-id", List.of(ONE)));
    }

    @Test
    @DisplayName("a partial list keeps what is usable and counts the rest")
    void a_partial_list_is_kept() {
        CommentsDeleted event = CommentsDeleted.of(MEME, Arrays.asList(ONE, "not-an-id", null, TWO))
                .orElseThrow();
        assertEquals(List.of(ONE, TWO), event.commentIds());
        assertEquals(2, event.unusable());
    }

    @Test
    @DisplayName("an announcement that names nothing is a valid event")
    void naming_nothing_is_valid() {
        CommentsDeleted event = CommentsDeleted.of(MEME, List.of()).orElseThrow();
        assertEquals(List.of(), event.commentIds());
        assertEquals(0, event.unusable());
    }

    @Test
    @DisplayName("the ids the event carries cannot be changed under it")
    void the_ids_are_copied() {
        CommentsDeleted event = CommentsDeleted.of(MEME, List.of(ONE)).orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> event.commentIds().add(TWO));
    }

    @Test
    @DisplayName("the message names its type, its meme, its comments and its version")
    void the_wire_shape() {
        CommentsDeleted event = CommentsDeleted.of(MEME, List.of(ONE, TWO)).orElseThrow();
        assertEquals(Map.of("type", "COMMENTS_DELETED",
                        "memeId", MEME,
                        "commentIds", List.of(ONE, TWO),
                        "version", 1),
                event.fields());
    }
}
