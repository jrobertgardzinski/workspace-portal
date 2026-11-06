package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A law, in the spirit of ADR 0006: memes and comments are TWINS, and where the twins must agree,
 * the build says so instead of a person noticing.
 *
 * <p>The two services are built the same way on purpose — same framework, same outbox, same
 * consumer, same probes. That similarity is what makes a difference between them so hard to see:
 * every earlier drift was found by reading the two files side by side and remembering what the other
 * one said, which works right up until it does not. P13 found the retention setting on one and not
 * the other; P18 found the stall threshold on one and not the other. Same shape, twice.
 *
 * <p>Two kinds of agreement, and the difference matters:
 *
 * <ul>
 *   <li>{@link #SAME_VALUE} — the setting means the same thing in both and the VALUE must match
 *       exactly. A log pattern that prints the correlation id in one service and not the other makes
 *       a saga untraceable at exactly the moment somebody is tracing it.</li>
 *   <li>{@link #SAME_DEFAULT} — each service reads its own environment variable, so the keys and the
 *       variable names differ by design; what must match is the DEFAULT, because that is what runs
 *       when nobody sets anything, which is most of the time.</li>
 * </ul>
 *
 * <p>What is deliberately NOT compared: ports, database URLs, service URLs, rate limits and the
 * purge policy. Those differ because the services differ — memes deletes what it holds, comments
 * anonymises it, and that was a decision, not a drift.
 */
@Epic("Infrastructure")
@Feature("Twin services agreement")
class TwinsAgreeWhereTheyMustTest {

    private static final Path MEMES = Path.of("src/main/resources/application.properties");
    // comments-infrastructure, not the repo root: comments was cut into one module per layer on
    // 2026-09-24, and its shipped properties moved with the rest of src/main/resources. The old
    // path did not fail — loadTwinOrSkip() ASSUMES the twin may be absent, because a developer
    // may have only one of the two repos cloned — so this law went quietly dormant instead,
    // which is the exact shape of failure this estate keeps paying for.
    private static final Path COMMENTS = Path.of(
            "../../microservice-comments/comments-infrastructure/src/main/resources/application.properties");

    /** Same key, same value, in both services. */
    private static final Set<String> SAME_VALUE = Set.of(
            "spring.kafka.consumer.auto-offset-reset",
            "spring.kafka.listener.idle-event-interval",
            "logging.pattern.console",
            "management.endpoints.web.exposure.include",
            "management.endpoint.health.probes.enabled",
            "management.endpoint.health.group.readiness.include",
            "management.endpoint.health.group.liveness.include",
            "management.endpoint.health.show-details");

    /**
     * Same meaning under different names: {@code memes.outbox.retention-hours} and
     * {@code comments.outbox.retention-hours} read {@code MEMES_...} and {@code COMMENTS_...}, and
     * only the fallback baked into the file has to be the same.
     */
    private static final List<String[]> SAME_DEFAULT = List.of(
            new String[]{"memes.outbox.retention-hours", "comments.outbox.retention-hours"},
            new String[]{"outbox.republish-interval-ms", "outbox.republish-interval-ms"},
            new String[]{"memes.saga.listener-stall-seconds", "comments.saga.listener-stall-seconds"},
            new String[]{"spring.kafka.producer.properties.max.block.ms",
                    "spring.kafka.producer.properties.max.block.ms"},
            new String[]{"spring.kafka.producer.properties.delivery.timeout.ms",
                    "spring.kafka.producer.properties.delivery.timeout.ms"},
            new String[]{"spring.kafka.producer.properties.request.timeout.ms",
                    "spring.kafka.producer.properties.request.timeout.ms"});

    @Test
    void the_twins_carry_the_same_value_where_the_setting_means_the_same_thing() throws IOException {
        Properties memes = load(MEMES);
        Properties comments = loadTwinOrSkip();

        List<String> drifted = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(SAME_VALUE)) {
            String here = memes.getProperty(key);
            String there = comments.getProperty(key);
            if (here == null || there == null || !here.equals(there)) {
                drifted.add(key + ": memes=" + here + " comments=" + there);
            }
        }

        assertEquals(List.of(), drifted,
                "the twins disagree on settings that mean the same thing in both — either the"
                        + " difference is deliberate, in which case take the key out of this law and"
                        + " say why, or one of them was changed and the other was forgotten");
    }

    @Test
    void the_twins_fall_back_to_the_same_default_when_nothing_is_configured() throws IOException {
        Properties memes = load(MEMES);
        Properties comments = loadTwinOrSkip();

        List<String> drifted = new ArrayList<>();
        for (String[] pair : SAME_DEFAULT) {
            String here = defaultOf(memes.getProperty(pair[0]));
            String there = defaultOf(comments.getProperty(pair[1]));
            if (here == null || there == null || !here.equals(there)) {
                drifted.add(pair[0] + "=" + here + " vs " + pair[1] + "=" + there);
            }
        }

        assertEquals(List.of(), drifted,
                "the twins fall back to different values when nothing is set in the environment —"
                        + " which is what happens on a developer machine and in every test, so this is"
                        + " the configuration people actually meet");
    }

    /**
     * The default baked into {@code ${VAR:default}}, or the whole value when there is no
     * placeholder. A key that stops being configurable at all still has a default to compare.
     */
    private static String defaultOf(String value) {
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        if (value.startsWith("${") && value.endsWith("}") && colon > 0) {
            return value.substring(colon + 1, value.length() - 1);
        }
        return value;
    }

    /**
     * The twin lives in ITS OWN git repository. Side by side — in the portal workspace and in the
     * portal CI job that checks all four services out together — this law runs. In the memes repo's
     * own CI there is nothing to compare against, and a law that fails there would be failing over
     * a file that was never meant to be present.
     */
    private static Properties loadTwinOrSkip() throws IOException {
        Assumptions.assumeTrue(Files.exists(COMMENTS),
                "the comments service is not checked out next to this one — nothing to compare");
        return load(COMMENTS);
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(Files.readString(file)));
        return properties;
    }
}
