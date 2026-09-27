package com.jrobertgardzinski.deletion;

import com.jrobertgardzinski.unitofwork.UnitOfWork;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The contract for a hop that passes the baton on: the announcement must be made inside the same
 * unit of work as the drop, or a crash between the two loses it for good — nothing in this
 * choreography ever re-derives a lost announcement, because a redelivered event finds an empty
 * thread and deliberately says nothing.
 *
 * <p>Only microservice-comments announces; microservice-user-collections is the cascade's end and
 * extends {@link CascadeHopContractTest} alone.
 */
public abstract class AtomicHopContractTest extends CascadeHopContractTest {

    // Single-threaded on purpose, not thread-safe by omission: this whole fixture runs on the
    // JUnit test thread, synchronously, so `inside` only ever answers "is the announcement being
    // made FROM WITHIN unitOfWork.run()'s step, right now, on this same call stack" — a call-
    // ordering question, not a concurrency one.
    private boolean inside;

    private final List<String> announced = new ArrayList<>();
    private final List<String> announcedInside = new ArrayList<>();

    /** The hop's unit of work: the subclass hands this to the participant it builds. */
    protected final UnitOfWork unitOfWork = step -> {
        inside = true;
        try {
            step.run();
        } finally {
            inside = false;
        }
    };

    /** The subclass's fake announcer calls this instead of reaching a broker. */
    protected final void announcement(String memeId) {
        announced.add(memeId);
        if (inside) {
            announcedInside.add(memeId);
        }
    }

    @Test
    @DisplayName("the announcement is made inside the same unit of work as the drop")
    void the_announcement_is_made_inside_the_unit_of_work() {
        givenMemeHas(3);
        handle(memeDeleted());
        assertEquals(List.of(MEME), announcedInside);
    }

    @Test
    @DisplayName("a hop that dropped nothing announces nothing")
    void a_hop_that_dropped_nothing_announces_nothing() {
        givenMemeHas(0);
        handle(memeDeleted());
        assertEquals(List.of(), announced);
    }

    @Test
    @DisplayName("a redelivered deletion announces nothing a second time")
    void a_redelivered_deletion_announces_nothing_twice() {
        givenMemeHas(3);
        handle(memeDeleted());
        handle(memeDeleted());
        assertEquals(List.of(MEME), announced);
    }
}
