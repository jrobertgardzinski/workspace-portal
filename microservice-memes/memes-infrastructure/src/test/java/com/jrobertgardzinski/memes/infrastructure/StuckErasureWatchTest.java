package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import java.util.Optional;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.MemeStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alarm on a lost closure command — the only thing the passage of time is allowed to buy in
 * this design, and the piece that had no test at all.
 *
 * <p>What it watches for is silent by construction: the saga marked a leaver's memes, the closure
 * never arrived, and from then on nothing is broken, nothing throws, and a query simply returns
 * fewer rows for ever. The pictures are hidden (which the leaver asked for) and not erased (which
 * the GDPR asked for), and without this watch nobody would ever learn the difference. A gauge that
 * silently stopped being fed would restore exactly that silence, so it is worth pinning.
 */
@Epic("Saga")
@Feature("Stuck erasure alarm")
class StuckErasureWatchTest {

    private static final UserId LEAVER = UserId.random();
    private static final Instant MARKED_AT = Instant.parse("2026-08-08T10:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void tapTheLog() {
        logLines.start();
        watchLogger().addAppender(logLines);
    }

    @AfterEach
    void untapTheLog() {
        watchLogger().detachAppender(logLines);
        logLines.stop();
    }

    private static Logger watchLogger() {
        return (Logger) LoggerFactory.getLogger(StuckErasureWatch.class);
    }

    /**
     * ONE watch per test, with a mutable backlog underneath. Micrometer keeps the FIRST gauge
     * registered under a given id and drops later ones, so a second instance on the same registry
     * would leave the assertions reading a gauge nothing feeds — a green test proving nothing.
     */
    private final MutableBacklog backlog = new MutableBacklog();

    private StuckErasureWatch watchingAt(Instant now) {
        // the real chain, not a stand-in: the use case decides, the adapter names the gauge, and
        // this class only runs them on a schedule. Assembled here because that is what a
        // composition root does, and the assertions below are about the three working together
        return new StuckErasureWatch(new com.jrobertgardzinski.memes.system.WatchErasureBacklog(
                backlog, new com.jrobertgardzinski.memes.config.ErasureTolerance(Duration.ofMinutes(30)),
                new MicrometerObservations(meters), Clock.fixed(now, ZoneOffset.UTC)));
    }

    private double gauge() {
        return meters.find("memes.erasure.backlog").gauge() == null ? -1
                : meters.find("memes.erasure.backlog").gauge().value();
    }

    /** A store whose backlog answer — or failure — is whatever the test currently says it is. */
    private static final class MutableBacklog implements MemeErasure {
        private List<MemeMetadata> stuck = List.of();
        private RuntimeException failure;

        void holds(List<MemeMetadata> marks) {
            this.stuck = marks;
            this.failure = null;
        }

        void cannotBeRead(RuntimeException why) {
            this.failure = why;
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
            if (failure != null) {
                throw failure;
            }
            return stuck;
        }
    }

    private static MemeMetadata marked() {
        return new MemeMetadata("m1", Optional.of(LEAVER), "png", MemeStatus.PENDING_ERASURE, MARKED_AT);
    }

    @Test
    @DisplayName("an empty backlog is the normal case: gauge at zero and not a word said")
    void a_clear_backlog_says_nothing() {
        watchingAt(MARKED_AT).watch();

        assertEquals(0, gauge());
        assertEquals(List.of(), logLines.list, "an alarm that speaks when nothing is wrong is an"
                + " alarm operators learn to scroll past");
    }

    @Test
    @DisplayName("an overdue mark is counted and said out loud — without naming the leaver")
    void an_overdue_mark_is_alarmed_on() {
        backlog.holds(List.of(marked()));

        watchingAt(MARKED_AT.plus(Duration.ofHours(2))).watch();

        assertEquals(1, gauge());
        String said = logLines.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(said.contains("hidden but NOT erased"),
                "an operator must be told what state the data is in: " + said);
        assertTrue(said.contains("delete it on a"),
                "and that nothing will fix it by itself — that is the design, not a bug: " + said);
        assertFalse(said.contains(LEAVER.toString()),
                "but never who they belong to: the authors of these memes are exactly the people"
                        + " this service is trying to forget — " + said);
    }

    @Test
    @DisplayName("the closure landing clears the alarm by itself — that is why it is a gauge")
    void the_backlog_falls_back_to_zero() {
        backlog.holds(List.of(marked()));
        StuckErasureWatch watch = watchingAt(MARKED_AT.plus(Duration.ofHours(2)));
        watch.watch();
        assertEquals(1, gauge());

        backlog.holds(List.of());   // the closure finally arrives and the memes are erased
        watch.watch();

        assertEquals(0, gauge(), "a counter would still read 1 here for ever");
    }

    @Test
    @DisplayName("a register that cannot be read keeps its last value instead of reporting zero")
    void an_unreadable_backlog_is_loud_not_reassuring() {
        backlog.holds(List.of(marked()));
        StuckErasureWatch watch = watchingAt(MARKED_AT.plus(Duration.ofHours(2)));
        watch.watch();

        backlog.cannotBeRead(new DataAccessResourceFailureException("database away"));
        watch.watch();

        assertEquals(1, gauge(),
                "reporting zero would turn a failed read into \"the backlog is clear\" — the exact"
                        + " reassurance this alarm exists to withhold");
        assertTrue(logLines.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .anyMatch(line -> line.contains("keeps its last value")),
                "and it says so: " + logLines.list);
    }
}
