package com.jrobertgardzinski.memes.system;

import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.memes.config.ErasureTolerance;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.Observation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Counts the obligations this service is sitting on — and does nothing about them on purpose.
 *
 * <p>A meme is marked {@code PENDING_ERASURE} by the saga's first, reversible step, and only the
 * orchestrator's closure turns that mark into a delete. A mark standing longer than any saga can
 * legitimately last therefore means the closure never arrived. The content is invisible, which is
 * what the leaver asked for, but it is still on disk, which is not what the law asked for — and
 * nobody would find out, because the failure is silent by construction: nothing throws, a query
 * simply returns fewer rows for ever.
 *
 * <p><strong>Why this does not erase them.</strong> Deleting on the passage of time means guessing
 * what the orchestrator decided, and the guess is wrong exactly when it is expensive: a saga stuck
 * for an hour because a sibling is down may still COMPENSATE, and content erased on a timer cannot
 * come back. The saga's state is knowable; this service is just not the one that knows it. So the
 * backlog is stated, and an operator acts on it (re-drive the closure, or compensate).
 *
 * <p>It states the fact on EVERY pass, zero included — the question is "how many right now", so
 * saying nothing would leave yesterday's answer standing as if it were today's.
 */
public class WatchErasureBacklog {

    private final MemeErasure erasure;
    private final ErasureTolerance tolerance;
    private final Observations<Observation> observations;
    private final Clock clock;

    public WatchErasureBacklog(MemeErasure erasure, ErasureTolerance tolerance,
                               Observations<Observation> observations, Clock clock) {
        this.erasure = erasure;
        this.tolerance = tolerance;
        this.observations = observations;
        this.clock = clock;
    }

    /** Answers what it stated, so a caller can log the detail without asking the database twice. */
    public Observation.ErasureBacklog execute() {
        Instant now = clock.instant();
        List<MemeMetadata> overdue = erasure.pendingSince(tolerance.overdueBefore(now));
        if (overdue.isEmpty()) {
            observations.record(Observation.ErasureBacklog.NONE);
            return Observation.ErasureBacklog.NONE;
        }
        // the OLDEST mark is what decides how bad this is; the list is ordered by the adapter's
        // query, and an age is not personal data — which matters here, because the people behind
        // these memes are the ones this service is trying to forget
        Duration oldest = Duration.between(overdue.getFirst().markedForErasureAt(), now);
        Observation.ErasureBacklog backlog = new Observation.ErasureBacklog(overdue.size(), oldest);
        observations.record(backlog);
        return backlog;
    }
}
