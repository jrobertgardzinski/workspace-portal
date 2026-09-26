package com.jrobertgardzinski.portal.closure.memes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code MemeRepository} axis {@link HeapMemes} adds on top of the inherited
 * {@code FakeMemeErasure}: every read must hide a meme a running saga has marked, exactly like the
 * real adapter's {@code active_memes} view. The erasure axis itself is not retested here — it is
 * inherited unmodified, and memes-application's own {@code FakeMemeErasureTest} already dogfoods it
 * against the contract.
 */
class HeapMemesTest {

    private final HeapMemes memes = new HeapMemes();

    @Test
    @DisplayName("findMetadata and allIds hide a meme a running saga has marked")
    void gallery_reads_hide_marked_memes() {
        memes.posted("m1", "author@example.com");
        memes.store(memes.activeOf("author@example.com").get(0).markForErasure(Instant.now()));

        assertTrue(memes.findMetadata("m1").isEmpty());
        assertTrue(memes.allIds().isEmpty());
    }

    @Test
    @DisplayName("reassignAuthor and deleteById act on the row regardless of erasure status")
    void mutations_are_status_blind() {
        memes.posted("m1", "author@example.com");
        memes.store(memes.activeOf("author@example.com").get(0).markForErasure(Instant.now()));

        memes.reassignAuthor("m1", "deleted-account");
        assertEquals(1, memes.pendingOf("deleted-account").size());

        memes.deleteById("m1");
        assertEquals(0, memes.heldBy("deleted-account").size());
    }
}
