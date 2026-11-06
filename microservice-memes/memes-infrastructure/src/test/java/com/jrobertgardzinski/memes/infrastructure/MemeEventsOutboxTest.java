package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.outbox.OutboxEvent;
import com.jrobertgardzinski.outbox.OutboxRepublisher;
import com.jrobertgardzinski.outbox.OutboxTable;
import com.jrobertgardzinski.outbox.RepublisherSettings;
import com.jrobertgardzinski.outbox.TransactionalOutbox;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The outbox's durability promise, exercised end to end against the real table: a committed
 * delete whose send fails keeps its row until the republisher delivers it (same payload, same
 * eventId — the row key, so the redelivery is a recognizable duplicate for comments' idempotent
 * thread-drop); a happy-path event is published exactly once, because the republisher touches
 * neither published rows nor fresh ones still owed a first attempt.
 *
 * <p>Round 10 moved the machinery into the shared kernel library and left this file where it was,
 * asserting the same seven things about the same table. That is the point of keeping it here: the
 * library has its own tests against a bare H2 and a fake dispatch, but only THIS service can say
 * whether the guarantee still holds where it is actually used — real Spring transaction manager,
 * real Flyway schema, real {@code KafkaTemplate} contract, real property names. If a property
 * hardened in rounds 5-9 had been lost in the extraction, it would have shown up as a failure
 * here, and the fix would have belonged in the library rather than in this file.
 */
@Epic("Infrastructure")
@Feature("Meme events outbox")
@Story("Republisher and retention")
@SpringBootTest(classes = MemesApplication.class)
class MemeEventsOutboxTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    DataSource dataSource;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private SpringOutbox springOutbox;
    private TransactionalOutbox outbox;
    private KafkaMemeEvents events;
    private OutboxRepublisher republisher;

    private static final long RETENTION_HOURS = 24;

    /** The dials this service runs on, so the assertions below quote the configuration, not magic numbers. */
    private static final RepublisherSettings SETTINGS =
            RepublisherSettings.defaults(Duration.ofHours(RETENTION_HOURS));

    @BeforeEach
    void freshOutbox() {
        // exactly MemeOutboxConfig's wiring, with a mock broker: the same table name, the same
        // dispatch, the same republisher settings the production bean gets
        springOutbox = new SpringOutbox(dataSource, OutboxTable.named("meme_events_outbox"), clock,
                new KafkaMemeDispatch(kafka));
        outbox = springOutbox.outbox();
        events = new KafkaMemeEvents(springOutbox);
        republisher = MemeOutboxConfig.republisher(springOutbox, RETENTION_HOURS);
        jdbc.sql("DELETE FROM meme_events_outbox").update();
    }

    private void backdateBeyondMinAge(String memeId) {
        // the row is seconds old; the republisher only touches rows older than its minimum age, so
        // age it artificially — in production the 30s pass by themselves
        jdbc.sql("UPDATE meme_events_outbox SET created_at = DATEADD('SECOND', -60, created_at), published_at = DATEADD('SECOND', -60, published_at)"
                + " WHERE event_key = ?").params(memeId).update();
    }

    private boolean published(String memeId) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM meme_events_outbox WHERE event_key = ?")
                .params(memeId).query(Boolean.class).single();
    }

    /** A stored row for the retention tests — no send, no transaction, just the row. */
    private void appendDelivered(String eventId, String memeId, boolean delivered) {
        springOutbox.append(new OutboxEvent(eventId, KafkaMemeEvents.TOPIC,
                KafkaMemeEvents.MEME_DELETED, memeId, null, "{}"));
        if (delivered) {
            outbox.markPublished(eventId);
        }
    }

    @Test
    @DisplayName("commit + failed send: the republisher delivers the SAME event later and marks it published")
    void republisher_delivers_what_the_first_attempt_could_not() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        tx.executeWithoutResult(status -> events.memeDeleted("orphan-thread"));

        ArgumentCaptor<ProducerRecord<String, String>> firstTry = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(firstTry.capture());
        assertFalse(published("orphan-thread"), "the failed send must leave the row unpublished");

        // the broker comes back; the row comes of age; the republisher takes over
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        backdateBeyondMinAge("orphan-thread");
        republisher.runOnce();

        ArgumentCaptor<ProducerRecord<String, String>> redelivered = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(redelivered.capture());
        assertEquals(firstTry.getValue().value(), redelivered.getValue().value(),
                "the redelivery must be the SAME event — same payload, same deterministic eventId");
        assertTrue(published("orphan-thread"), "confirmed redelivery must mark the row");

        republisher.runOnce();
        verifyNoMoreInteractions(kafka);   // delivered means done — no third send
    }

    @Test
    @DisplayName("happy path: exactly one publication — the republisher does not double a marked row")
    void happy_path_publishes_exactly_once() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        tx.executeWithoutResult(status -> events.memeDeleted("gone"));

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        // the confirmation is recorded on the producer's I/O thread and written by the pass — the
        // mark is JDBC, and doing it there froze every send the service made when the pool was full
        assertFalse(published("gone"), "not from the producer's I/O thread");

        republisher.runOnce();

        assertTrue(published("gone"));

        backdateBeyondMinAge("gone");   // even old enough to qualify, a published row is not re-sent
        republisher.runOnce();
        verifyNoMoreInteractions(kafka);
    }

    @Test
    @DisplayName("a fresh unpublished row is left alone — its first after-commit attempt may still be in flight")
    void republisher_leaves_fresh_rows_to_the_first_attempt() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        tx.executeWithoutResult(status -> events.memeDeleted("just-now"));
        verify(kafka, times(1)).send(any(ProducerRecord.class));

        republisher.runOnce();   // no backdating: the row is seconds old

        verifyNoMoreInteractions(kafka);
        assertFalse(published("just-now"), "still waiting for the republisher, once it is old enough");
    }

    @Test
    @DisplayName("retention: a delivered row past the threshold is reaped; a fresh delivered one and an old undelivered one stay")
    void retention_reaps_only_delivered_rows_past_the_threshold() {
        appendDelivered("e-old-done", "delivered-long-ago", true);
        appendDelivered("e-new-done", "delivered-just-now", true);
        appendDelivered("e-old-owed", "still-owed", false);
        backdateBeyondRetention("delivered-long-ago");
        backdateBeyondRetention("still-owed");
        // the pass will also re-try the aged unpublished row — keep the broker down so it stays
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        republisher.runOnce();

        assertFalse(rowExists("delivered-long-ago"),
                "a delivered event past retention has no value left — the row must go");
        assertTrue(rowExists("delivered-just-now"),
                "retention must not touch a delivered row that is younger than the threshold");
        assertTrue(rowExists("still-owed"),
                "an UNDELIVERED row is never reaped, however old — it still carries an obligation");
        assertFalse(published("still-owed"));
    }

    @Test
    @DisplayName("a payload wider than the old varchar(1024) is stored intact — TEXT since V6, no silent cliff")
    void a_payload_wider_than_the_old_column_survives_intact() {
        String fat = "{\"type\":\"SOME_RICHER_EVENT\",\"blob\":\"" + "x".repeat(4096) + "\"}";

        springOutbox.append(new OutboxEvent("fat-event", KafkaMemeEvents.TOPIC, "SOME_RICHER_EVENT",
                "fat", null, fat));

        assertEquals(fat, jdbc.sql("SELECT payload FROM meme_events_outbox WHERE id = ?")
                        .params("fat-event").query(String.class).single(),
                "the payload must come back byte-for-byte — a future event type must not be cut short");
    }

    @Test
    @DisplayName("retention is batched and capped: a huge backlog goes 500 at a time, exactly as many batches as promised, the rest next pass")
    void retention_deletes_in_capped_batches() {
        // 1200 delivered rows past retention — the shape of the FIRST pass after this feature
        // ships on a gallery that has been deleting memes for months. One statement over all of
        // them would be a single long transaction on the scheduler thread.
        for (int i = 0; i < 1200; i++) {
            appendDelivered("bulk-" + i, "bulk", true);
        }
        backdateBeyondRetention("bulk");
        int batchRows = SETTINGS.retentionBatchRows();

        int firstPass = outbox.deletePublishedOlderThan(Duration.ofHours(RETENTION_HOURS), batchRows, 2);

        assertEquals(2 * batchRows, firstPass,
                "a capped pass deletes exactly what the cap promises — no more (the point) and no"
                        + " less (or a backlog would never drain)");
        assertEquals(1200 - 2 * batchRows, rowCount("bulk"), "the remainder waits for the next pass");

        int secondPass = outbox.deletePublishedOlderThan(Duration.ofHours(RETENTION_HOURS), batchRows, 2);

        assertEquals(1200 - 2 * batchRows, secondPass, "the next pass takes what is left and stops early");
        assertEquals(0, rowCount("bulk"));
        assertEquals(0, outbox.deletePublishedOlderThan(Duration.ofHours(RETENTION_HOURS), batchRows, 2),
                "an empty backlog costs one short statement, not a batch loop");
    }

    @Test
    @DisplayName("a retention of zero or less refuses the boot, naming the property and echoing the value")
    void a_non_positive_retention_refuses_to_start() {
        for (long broken : new long[]{0, -1, -24}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> MemeOutboxConfig.republisher(springOutbox, broken));
            // the library owns no configuration namespace, so the name travels from HERE into its
            // message — a service that forgot to pass it would leave the operator grepping a
            // codebase they do not have
            assertEquals("memes.outbox.retention-hours", MemeOutboxConfig.RETENTION_PROPERTY,
                    "the constant must spell the dial exactly as application.properties does");
            assertTrue(refused.getMessage().contains(MemeOutboxConfig.RETENTION_PROPERTY),
                    "the operator who set the dial must read its NAME: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(String.valueOf(broken)),
                    "…and the value they set: " + refused.getMessage());
        }
    }

    private int rowCount(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM meme_events_outbox WHERE event_key = ?")
                .params(memeId).query(Integer.class).single();
    }

    private void backdateBeyondRetention(String memeId) {
        // published_at moves too: retention measures its window from the DELIVERY now
        jdbc.sql("UPDATE meme_events_outbox SET created_at = DATEADD('HOUR', ?, created_at),"
                        + " published_at = DATEADD('HOUR', ?, published_at)"
                + " WHERE event_key = ?")
                .params(-(RETENTION_HOURS + 1), -(RETENTION_HOURS + 1), memeId).update();
    }

    private boolean rowExists(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM meme_events_outbox WHERE event_key = ?")
                .params(memeId).query(Integer.class).single() > 0;
    }
}
