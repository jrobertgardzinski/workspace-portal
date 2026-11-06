package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.identity.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link MemeErasure} promises, asked of EVERY implementation — the JDBC adapter the service
 * runs on and each stand-in used in its place. A stand-in that drifts does not fail; it makes a
 * green suite say something about a service that does not exist.
 *
 * <p>Fresh ids per test method, because one implementation is a database other suites share.
 */
public abstract class MemeErasureContractTest {

    private static final Instant NOON = Instant.parse("2026-09-24T12:00:00Z");

    private final String run = java.util.UUID.randomUUID().toString().substring(0, 8);
    private final UserId alice = UserId.random();
    private final UserId bob = UserId.random();
    private final String first = "m1-" + run;
    private final String second = "m2-" + run;
    private final String third = "m3-" + run;

    protected abstract MemeErasure erasure();

    /** Put an ACTIVE meme in, however this implementation stores one. */
    protected abstract void givenActiveMeme(String id, Optional<UserId> authorId);

    private void givenActiveMeme(String id, UserId author) {
        givenActiveMeme(id, Optional.of(author));
    }

    private MemeMetadata theOnly(List<MemeMetadata> found) {
        assertEquals(1, found.size(), "expected exactly one meme, got " + found);
        return found.get(0);
    }

    @Test
    @DisplayName("active and pending are the two halves of one author's memes")
    protected void active_and_pending_split_by_status() {
        givenActiveMeme(first, alice);
        givenActiveMeme(second, alice);
        givenActiveMeme(third, bob);

        assertEquals(2, erasure().activeOf(alice).size());
        assertEquals(List.of(), erasure().pendingOf(alice));

        erasure().store(theOnly(erasure().activeOf(bob)).markForErasure(NOON));

        assertEquals(List.of(), erasure().activeOf(bob));
        assertEquals(NOON, theOnly(erasure().pendingOf(bob)).markedForErasureAt());
    }

    @Test
    @DisplayName("the id is the key, and a row without one belongs to nobody")
    protected void rows_are_keyed_by_the_authors_id() {
        givenActiveMeme(first, Optional.of(alice));
        givenActiveMeme(second, Optional.of(alice));
        givenActiveMeme(third, Optional.empty());

        assertEquals(2, erasure().activeOf(alice).size(), "two rows, one person");
        assertEquals(List.of(), erasure().activeOf(bob), "and the anonymised row is nobody's");
    }

    @Test
    @DisplayName("a restored meme is active again and carries no mark")
    protected void restoring_puts_it_back() {
        givenActiveMeme(first, alice);
        erasure().store(theOnly(erasure().activeOf(alice)).markForErasure(NOON));

        erasure().store(theOnly(erasure().pendingOf(alice)).restore());

        MemeMetadata back = theOnly(erasure().activeOf(alice));
        assertEquals(null, back.markedForErasureAt(), "a restored meme owes nobody an instant");
        assertEquals(List.of(), erasure().pendingOf(alice));
    }

    @Test
    @DisplayName("store writes the ERASURE state and nothing else — the author is not this port's business")
    protected void store_does_not_write_the_author_id() {
        givenActiveMeme(first, alice);
        MemeMetadata held = theOnly(erasure().activeOf(alice));

        // a stale copy carrying somebody else's identity — which is what the closure hands over a
        // line after it has anonymised the row. If this port wrote the author id back, the
        // anonymisation would be undone by the very next call
        erasure().store(new MemeMetadata(held.id(), Optional.of(bob),
                held.format(), held.markForErasure(NOON).status(), NOON));

        assertEquals(List.of(), erasure().pendingOf(bob),
                "the author id moved: this port wrote a column that is not its own");
        assertEquals(first, theOnly(erasure().pendingOf(alice)).id());
    }

    @Test
    @DisplayName("pendingSince is STRICTLY before the cutoff — the adapters ask `marked_for_erasure_at < ?`")
    protected void pending_since_excludes_the_cutoff_itself() {
        givenActiveMeme(first, alice);
        erasure().store(theOnly(erasure().activeOf(alice)).markForErasure(NOON));

        assertEquals(List.of(), erasure().pendingSince(NOON),
                "a meme marked AT the cutoff is not yet overdue");
        assertTrue(erasure().pendingSince(NOON.plusSeconds(1)).stream()
                        .anyMatch(meme -> meme.id().equals(first)),
                "a meme marked before the cutoff is overdue");
    }
}
