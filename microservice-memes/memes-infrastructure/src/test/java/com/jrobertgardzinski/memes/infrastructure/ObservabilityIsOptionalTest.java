package com.jrobertgardzinski.memes.infrastructure;

import java.util.Optional;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.memes.system.WatchErasureBacklog;
import com.jrobertgardzinski.memes.config.ErasureTolerance;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.MemeStatus;
import com.jrobertgardzinski.memes.domain.Observation;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The boundary, asserted instead of promised: observability is a layer this service can be
 * assembled WITHOUT.
 *
 * <p>Two halves, and both are needed. The first says nothing above infrastructure has ever heard of
 * the watching tool — the day Micrometer is swapped out, the search below is what proves the blast
 * radius is one package. The second says the work itself does not depend on being watched: run the
 * same use case with silence in place of the adapter and the answer is identical.
 *
 * <p>Honest about its own reach: Micrometer is on this module's classpath whatever this test says,
 * because the adapter lives here. What is pinned is the direction of the dependency — domain,
 * config and application know about facts, and only infrastructure knows what a metric is. When the
 * adapter moves to a module of its own, this test is the thing that will already be true.
 */
@Epic("Architecture")
@Feature("Observability is a layer, not a dependency")
class ObservabilityIsOptionalTest {

    private static final UserId SOMEBODY = UserId.random();

    /** The layers that must not name a tool — everything except the adapters. */
    private static final List<Path> ABOVE_INFRASTRUCTURE = List.of(
            Path.of("../memes-domain/src/main/java"),
            Path.of("../memes-config/src/main/java"),
            Path.of("../memes-application/src/main/java"));

    /**
     * Vendor words, not concepts. {@code observ} is deliberately absent: {@code Observation} and
     * {@code Observations} ARE the domain's own vocabulary and must be free to appear.
     */
    private static final List<String> TOOLS = List.of(
            "micrometer", "prometheus", "meterregistry", "opentelemetry", "otel",
            "traceparent", "grafana", "loki", "tempo", "io.opentelemetry");

    @Test
    @DisplayName("no layer above infrastructure names the tool that watches it")
    void the_tool_stays_in_the_adapter() throws IOException {
        for (Path layer : ABOVE_INFRASTRUCTURE) {
            try (Stream<Path> tree = Files.walk(layer)) {
                List<String> leaks = tree
                        .filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> mentionsATool(read(file)))
                        .map(Path::toString)
                        .toList();
                assertEquals(List.of(), leaks,
                        "these files below the adapters name a watching tool — that is the coupling"
                                + " this layering exists to prevent, and the reason a swap would"
                                + " otherwise reach the business code: " + leaks);
            }
        }
    }

    @Test
    @DisplayName("with nothing watching, the work is unchanged — the answer is the same")
    void the_service_works_unwatched() {
        Instant markedAt = Instant.parse("2026-08-08T10:00:00Z");
        MemeErasure backlog = holding(List.of(
                new MemeMetadata("m1", Optional.of(SOMEBODY), "png", MemeStatus.PENDING_ERASURE, markedAt)));
        Observations<Observation> silence = observation -> { };

        Observation.ErasureBacklog said = new WatchErasureBacklog(backlog,
                new ErasureTolerance(Duration.ofMinutes(30)), silence,
                Clock.fixed(markedAt.plus(Duration.ofHours(2)), ZoneOffset.UTC)).execute();

        // the obligation is still counted and still answered to the caller; the only thing missing
        // is somebody to tell — which is what "no watcher" means and all it may mean
        assertEquals(1, said.marked());
        assertEquals(Duration.ofHours(2), said.oldest());
    }

    private static boolean mentionsATool(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return TOOLS.stream().anyMatch(lower::contains);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read " + file, unreadable);
        }
    }

    private static MemeErasure holding(List<MemeMetadata> marks) {
        return new MemeErasure() {

            public List<MemeMetadata> activeOf(com.jrobertgardzinski.identity.UserId author) {
                return List.of();
            }

            public List<MemeMetadata> pendingOf(com.jrobertgardzinski.identity.UserId author) {
                return List.of();
            }

            public void store(MemeMetadata state) {
            }

            public List<MemeMetadata> pendingSince(Instant cutoff) {
                return marks;
            }
        };
    }
}
