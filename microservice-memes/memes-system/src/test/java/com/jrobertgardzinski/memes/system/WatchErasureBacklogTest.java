package com.jrobertgardzinski.memes.system;

import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.memes.config.ErasureTolerance;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.MemeStatus;
import com.jrobertgardzinski.memes.domain.Observation;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The DECISION behind the stuck-erasure alarm, with nothing watching.
 *
 * <p>Worth its own test at this level precisely because the thing it decides is not a metric: "how
 * many obligations am I sitting on, and how old is the worst" is a sentence about account deletion,
 * and it has to be right whether the answer ends up in Prometheus, in a log line, or nowhere at all.
 * The infrastructure test next door pins how that sentence is spelled once a tool is listening.
 */
@Epic("Saga")
@Feature("Stuck erasure alarm")
class WatchErasureBacklogTest {

    private static final UserId SOMEBODY = UserId.random();

    private static final Instant MARKED_AT = Instant.parse("2026-08-08T10:00:00Z");
    private static final ErasureTolerance HALF_AN_HOUR =
            new ErasureTolerance(Duration.ofMinutes(30));

    private final Backlog backlog = new Backlog();
    private final List<Observation> stated = new ArrayList<>();

    private Observation.ErasureBacklog watchAt(Instant now) {
        return new WatchErasureBacklog(backlog, HALF_AN_HOUR, stated::add,
                Clock.fixed(now, ZoneOffset.UTC)).execute();
    }

    @Test
    @DisplayName("a clear backlog is STATED, not passed over in silence")
    void zero_is_a_fact_too() {
        Observation.ErasureBacklog said = watchAt(MARKED_AT);

        assertEquals(0, said.marked());
        assertEquals(List.of(Observation.ErasureBacklog.NONE), stated,
                "saying nothing would leave the last answer standing as if it were this one's —"
                        + " the whole point of asking 'how many RIGHT NOW'");
    }

    @Test
    @DisplayName("only marks past the tolerance count, and the OLDEST decides how bad it is")
    void the_oldest_mark_measures_the_damage() {
        backlog.holds(List.of(marked("m1", MARKED_AT), marked("m2", MARKED_AT.plusSeconds(60))));

        Observation.ErasureBacklog said = watchAt(MARKED_AT.plus(Duration.ofHours(2)));

        assertEquals(2, said.marked());
        assertEquals(Duration.ofHours(2), said.oldest());
        assertEquals(List.of(said), stated);
    }

    @Test
    @DisplayName("the tolerance is what the store is asked for — not a filter applied afterwards")
    void the_cutoff_goes_to_the_store() {
        watchAt(MARKED_AT.plus(Duration.ofHours(2)));

        assertEquals(MARKED_AT.plus(Duration.ofMinutes(90)), backlog.askedFor,
                "the query carries the tolerance, so a store can index on it instead of handing"
                        + " over every mark it holds");
    }

    @Test
    @DisplayName("what it states is what it answers — the caller need not ask the store twice")
    void the_answer_is_the_statement() {
        backlog.holds(List.of(marked("m1", MARKED_AT)));

        Observation.ErasureBacklog said = watchAt(MARKED_AT.plus(Duration.ofHours(2)));

        assertEquals(1, stated.size());
        assertInstanceOf(Observation.ErasureBacklog.class, stated.getFirst());
        assertEquals(said, stated.getFirst());
    }

    private static MemeMetadata marked(String id, Instant at) {
        return new MemeMetadata(id, Optional.of(SOMEBODY), "png", MemeStatus.PENDING_ERASURE, at);
    }

    /** Answers whatever the test holds, and remembers the cutoff it was asked for. */
    private static final class Backlog implements MemeErasure {
        private List<MemeMetadata> stuck = List.of();
        private Instant askedFor;

        void holds(List<MemeMetadata> marks) {
            this.stuck = marks;
        }

        public List<MemeMetadata> activeOf(com.jrobertgardzinski.identity.UserId author) {
            return List.of();
        }

        public List<MemeMetadata> pendingOf(com.jrobertgardzinski.identity.UserId author) {
            return List.of();
        }

        public void store(MemeMetadata state) {
        }

        public List<MemeMetadata> pendingSince(Instant cutoff) {
            this.askedFor = cutoff;
            return stuck;
        }
    }
}
