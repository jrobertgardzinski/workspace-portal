package com.jrobertgardzinski.portal.heap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jrobertgardzinski.identity.UserId;

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

    private static final UserId AUTHOR = UserId.random();

    private final HeapMemes memes = new HeapMemes();

    @Test
    @DisplayName("findMetadata and allIds hide a meme a running saga has marked")
    void gallery_reads_hide_marked_memes() {
        memes.posted("m1", AUTHOR);
        memes.store(memes.activeOf(AUTHOR).get(0).markForErasure(Instant.now()));

        assertTrue(memes.findMetadata("m1").isEmpty());
        assertTrue(memes.allIds().isEmpty());
    }

    @Test
    @DisplayName("reassignAuthor and deleteById act on the row regardless of erasure status")
    void mutations_are_status_blind() {
        memes.posted("m1", AUTHOR);
        memes.store(memes.activeOf(AUTHOR).get(0).markForErasure(Instant.now()));

        memes.anonymise("m1");
        assertEquals(0, memes.heldBy(AUTHOR).size(), "an anonymised meme is nobody's, marked or not");

        memes.deleteById("m1");
        assertEquals(0, memes.signedByNobody().size());
    }
}
