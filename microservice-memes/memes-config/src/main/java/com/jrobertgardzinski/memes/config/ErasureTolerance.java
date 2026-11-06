package com.jrobertgardzinski.memes.config;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a meme may stay marked for erasure before the mark stops meaning "a saga is working on
 * it" and starts meaning "the closure never came".
 *
 * <p>It lives in config rather than in the watcher because it is the kind of number people argue
 * about: it trades a false alarm during a slow deployment against the hours a genuinely lost
 * erasure stays unnoticed, and that trade belongs to whoever answers for the obligation — not to
 * whichever class happens to run the query.
 *
 * <p>The default is derived, not felt. The orchestrator gives a purge
 * {@code OFFBOARDING_PURGE_TIMEOUT_SEC} (120s) and re-commands it {@code OFFBOARDING_PURGE_RETRIES}
 * times (3), so a case is decided inside about eight minutes; thirty leaves room for a slow
 * deployment's backlog and still turns a lost closure into an alarm on the same shift. Raising it
 * past the orchestrator's worst case is the only real mistake — below that the alarm fires on sagas
 * that are still perfectly alive.
 */
public record ErasureTolerance(Duration markStandsFor) {

    public static final ErasureTolerance DEFAULT = new ErasureTolerance(Duration.ofMinutes(30));

    public ErasureTolerance {
        if (markStandsFor.isNegative() || markStandsFor.isZero()) {
            throw new IllegalArgumentException("a mark must be allowed to stand for some time, was "
                    + markStandsFor);
        }
    }

    /** Marks older than this instant are evidence of a lost closure, not of a running saga. */
    public Instant overdueBefore(Instant now) {
        return now.minus(markStandsFor);
    }
}
