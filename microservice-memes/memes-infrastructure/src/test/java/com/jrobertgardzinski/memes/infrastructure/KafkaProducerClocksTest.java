package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A configuration pin, the same species as TransactionAwareDeletesTest's Hikari autocommit one:
 * it asserts that a property the CODE'S PROMISES depend on actually reaches the component that
 * honours it — here, the producer's blocking clocks.
 *
 * <p>What depends on them: {@link KafkaMemeDispatch} promises the shared outbox library that the
 * announcing thread never waits for the broker — half of the library's {@code Dispatch} contract,
 * and the half no library can check for itself: it has no broker client, so it can neither see nor
 * set a producer property. Property (h) of the outbox design is therefore, unavoidably, this
 * service's. {@code KafkaTemplate.send()} is asynchronous only once the
 * record is in the accumulator; reaching it blocks the CALLING thread on the metadata fetch
 * (a leaderless topic has none) and on buffer space for up to {@code max.block.ms}. Kafka's own
 * default is 60s, so a purge of N memes — announced from the Kafka LISTENER thread after a
 * single commit — would hold that thread N&times;60s against a leaderless {@code memes-events}:
 * WORSE than the N&times;5s {@code Future.get()} the outbox replaced, and the very
 * {@code max.poll.interval.ms} overrun (300s) that the outbox was built to avoid.
 *
 * <p>A broker outage cannot be staged with a mock KafkaTemplate — the blocking lives inside the
 * real producer — so this pins the configuration instead: change or drop the properties and this
 * fails, instead of the javadoc quietly becoming a lie.
 *
 * <p>CAVEAT, the same one the Hikari pin carries: these are env-overridable at DEPLOY time
 * (MEMES_KAFKA_MAX_BLOCK_MS and friends), where no test runs.
 */
@Epic("Infrastructure")
@Feature("Kafka configuration")
@Story("Producer clocks")
@SpringBootTest(classes = MemesApplication.class)
class KafkaProducerClocksTest {

    @Autowired
    ProducerFactory<?, ?> producerFactory;

    @Test
    @DisplayName("the producer really gets max.block.ms=5s — the ceiling the non-blocking first attempt is measured against")
    void the_producer_gets_the_configured_blocking_clocks() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();

        assertEquals("5000", String.valueOf(config.get("max.block.ms")),
                "the announcing thread (a purge runs on the Kafka listener thread) may wait this"
                        + " long per event and no longer — Kafka's 60s default would make"
                        + " KafkaMemeDispatch' 'never blocks' half of the Dispatch contract false");
        assertEquals("30000", String.valueOf(config.get("delivery.timeout.ms")),
                "same clock as offboarding's KafkaLoop and collections' PurgeCommandsConsumer");
        assertEquals("15000", String.valueOf(config.get("request.timeout.ms")),
                "same clock as offboarding's KafkaLoop and collections' PurgeCommandsConsumer");
    }
}
