package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A configuration pin, the same species as {@link KafkaProducerClocksTest}: it asserts that a
 * property the CODE'S PROMISES depend on actually reaches the component that honours it — here, the
 * consumer's starting position for a group with no committed offset.
 *
 * <p>What depends on it: Kafka's own default is {@code latest}, and {@code latest} loses records
 * without a trace. A consumer group that has not committed an offset yet — a fresh environment, or
 * Kafka switched on after this service ran without it — starts AFTER whatever
 * {@code content-commands} already holds, so purge commands published before the group's first poll
 * are never seen: no log line, no metric, a saga that times out and compensates for no visible
 * reason. {@code earliest} walks the retained history instead, and the purge being idempotent
 * ({@code PurgeCommandsListener}'s own contract), repeating it costs nothing — the reasoning
 * microservice-user-collections wrote down next to the very same setting.
 *
 * <p>The scenario cannot be staged here without a broker and two deployments, so this pins the
 * configuration instead: drop or misspell the property and this fails, instead of the first k3s
 * environment quietly skipping the deletions that were commanded before the group existed.
 */
@Epic("Infrastructure")
@Feature("Kafka configuration")
@Story("Consumer offset reset")
@SpringBootTest(classes = MemesApplication.class)
class KafkaConsumerOffsetResetTest {

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @Test
    @DisplayName("the consumer really gets auto.offset.reset=earliest — a group with no committed offset must read history, not skip it")
    void the_consumer_starts_from_the_earliest_offset() {
        Map<String, Object> config = consumerFactory.getConfigurationProperties();

        assertEquals("earliest", String.valueOf(config.get("auto.offset.reset")),
                "with Kafka's latest default, a consumer group with no committed offset starts"
                        + " AFTER the records already on the topic — purge commands published before"
                        + " this service's first poll would be skipped without a trace");
    }
}
