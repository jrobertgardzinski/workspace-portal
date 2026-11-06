package com.jrobertgardzinski.memes.domain;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jrobertgardzinski.identity.UserId;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two transitions the whole compensatable saga rests on, tested where they are DECIDED rather
 * than where they are stored. Everything above this record — the use cases, the adapters, the
 * orchestrator — can only be as correct as these four lines, and until this class existed the
 * rules were stated in prose (the javadoc, the ADR, a CHECK constraint) and asserted nowhere: the
 * use-case tests run on a fixed clock, so "a redelivery keeps the FIRST instant" was literally
 * unobservable there.
 */
@Epic("Domain")
@Feature("Erasure mark")
class MemeMetadataTest {

    private static final Instant FIRST_DELIVERY = Instant.parse("2026-08-08T10:00:00Z");
    private static final Instant REDELIVERY = FIRST_DELIVERY.plus(Duration.ofHours(1));

    private static final UserId LEAVER = UserId.random();

    private static MemeMetadata inTheGallery() {
        return new MemeMetadata("m1", LEAVER, "png");
    }

    /** What a kept meme of a closed account looks like: no id, so nobody's. */
    private static MemeMetadata anonymised() {
        return new MemeMetadata("m1", "png", MemeStatus.ACTIVE, null);
    }

    @Test
    @DisplayName("ownership is the id's: the same id under a new address still owns, an anonymised row is nobody's")
    void ownership_is_the_ids() {
        UserId alice = UserId.random();
        MemeMetadata meme = new MemeMetadata("m1", Optional.of(alice), "png", MemeStatus.ACTIVE, null);

        assertTrue(meme.isOwnedBy(alice));
        assertFalse(meme.isOwnedBy(UserId.random()), "another id is somebody else");
        assertFalse(anonymised().isOwnedBy(alice), "a row without an id has been anonymised: nobody's");
    }

    @Test
    @DisplayName("a fresh meme is ACTIVE and carries no mark")
    void the_shorthand_constructor_is_the_gallery_state() {
        MemeMetadata meme = inTheGallery();

        assertEquals(MemeStatus.ACTIVE, meme.status());
        assertEquals(null, meme.markedForErasureAt());
        assertFalse(meme.isPendingErasure());
    }

    @Test
    @DisplayName("marking reserves the meme and records when")
    void mark_sets_the_status_and_the_instant_together() {
        MemeMetadata marked = inTheGallery().markForErasure(FIRST_DELIVERY);

        assertTrue(marked.isPendingErasure());
        assertEquals(FIRST_DELIVERY, marked.markedForErasureAt());
        assertEquals(Optional.of(LEAVER), marked.authorId(), "and nothing else moves");
        assertEquals("png", marked.format());
    }

    @Test
    @DisplayName("a redelivered mark keeps the FIRST instant — the backlog measures an age")
    void marking_twice_does_not_rejuvenate_the_obligation() {
        // Kafka is at-least-once, so this command WILL arrive twice. If the second one reset the
        // instant, a mark redelivered every few minutes would never look old enough to alarm on,
        // and a lost closure command would stay invisible for exactly as long as the redeliveries
        // continue — the failure StuckErasureWatch exists to catch would hide behind the retry.
        MemeMetadata marked = inTheGallery().markForErasure(FIRST_DELIVERY);

        MemeMetadata again = marked.markForErasure(REDELIVERY);

        assertEquals(FIRST_DELIVERY, again.markedForErasureAt());
        assertSame(marked, again, "and it is the very same value: nothing to store, nothing to log");
    }

    @Test
    @DisplayName("restoring puts it back exactly as it was, and twice is once")
    void restore_is_the_inverse_and_is_idempotent() {
        MemeMetadata meme = inTheGallery();

        MemeMetadata restored = meme.markForErasure(FIRST_DELIVERY).restore();

        assertEquals(meme, restored, "the mark is fully reversible — that is the whole feature");
        assertSame(restored, restored.restore(), "and restoring an ACTIVE meme is a no-op");
    }

    @Test
    @DisplayName("restoring a meme nobody marked is a no-op, not an error")
    void restore_of_an_unmarked_meme_does_not_throw() {
        // the orchestrator compensates EVERY participant it commanded, and it cannot know which of
        // them got as far as marking — so this has to be silent rather than exceptional
        MemeMetadata meme = inTheGallery();

        assertSame(meme, meme.restore());
    }

    @Test
    @DisplayName("a mark without its instant — or an instant without its mark — cannot be built")
    void the_invariant_is_unrepresentable_not_merely_discouraged() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemeMetadata("m1", "png", MemeStatus.PENDING_ERASURE, null),
                "a mark with no instant would be invisible to the backlog alarm for ever");
        assertThrows(IllegalArgumentException.class,
                () -> new MemeMetadata("m1", "png", MemeStatus.ACTIVE, FIRST_DELIVERY),
                "an instant with no mark would be a meme the gallery shows and the reaper counts");
    }
}
