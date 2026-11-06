package com.jrobertgardzinski.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every hop of the cascade owes the ones after it, whatever it holds. A hop's own test
 * extends this and adds what is specific to what it drops.
 *
 * <p>Both hops act on {@link MemeDeleted}, so the promises are stated on it. Only
 * microservice-user-collections acts on {@link CommentsDeleted} as well — which is why the one
 * test that uses it here names nothing a hop could act on, and is therefore a promise both owe.
 */
public abstract class CascadeHopContractTest {

    /** The meme the whole cascade is about. */
    protected static final String MEME = "8f1c1a3e-2b4d-4c5e-9a7f-1d2e3b4c5a6d";

    protected abstract DeletionOutcome handle(MemeDeleted memeDeleted);

    protected abstract DeletionOutcome handle(CommentsDeleted commentsDeleted);

    /** Puts this many rows of {@link #MEME}'s in whatever this hop holds. */
    protected abstract void givenMemeHas(int rows);

    /** True while everything {@link #givenMemeHas} put there is still there. */
    protected abstract boolean nothingTouched();

    protected static MemeDeleted memeDeleted() {
        return MemeDeleted.of(MEME).orElseThrow();
    }

    @Test
    @DisplayName("the hop drops what the deletion names")
    void the_hop_drops_what_the_deletion_names() {
        givenMemeHas(3);
        assertEquals(new DeletionOutcome.Dropped(3), handle(memeDeleted()));
    }

    @Test
    @DisplayName("a redelivered deletion drops nothing: the hop is idempotent (ADR 0006)")
    void a_redelivered_deletion_drops_nothing() {
        givenMemeHas(3);
        handle(memeDeleted());
        assertInstanceOf(DeletionOutcome.Nothing.class, handle(memeDeleted()));
    }

    @Test
    @DisplayName("a deletion that names nothing is dropped without touching anything")
    void a_deletion_that_names_nothing_touches_nothing() {
        givenMemeHas(3);
        CommentsDeleted namesNothing = CommentsDeleted.of(MEME, List.of()).orElseThrow();
        assertInstanceOf(DeletionOutcome.Nothing.class, handle(namesNothing));
        assertTrue(nothingTouched());
    }
}
