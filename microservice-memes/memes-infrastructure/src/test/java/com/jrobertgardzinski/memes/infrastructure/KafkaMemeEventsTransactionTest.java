package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.outbox.OutboxTable;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MEME_DELETED must respect the delete/purge transaction it is announced from — and since round 5
 * the mechanism is a real outbox: the event row is written in the SAME transaction as the
 * teardown, so a rollback discards row and event alike; after the commit the send is attempted
 * WITHOUT blocking the announcing thread — the broker's completion callback marks the row
 * published once delivery is CONFIRMED (the mocks here complete their futures synchronously, so
 * the callback has run by the time the assertions look). A crash between commit and send leaves
 * an unpublished row for the republisher (see MemeEventsOutboxTest).
 *
 * <p>Round 10 replaced this service's own outbox classes with the shared kernel library, so every
 * assertion below is now also a statement about that library — wired exactly as
 * {@link MemeOutboxConfig} wires it in production, against the real {@code meme_events_outbox}
 * table V5/V6 created and a real {@code TransactionTemplate}. Nothing here was weakened for the
 * migration; the class names in the arrangement changed and the promises did not.
 */
@Epic("Infrastructure")
@Feature("Meme events outbox")
@Story("Transactional announce")
@SpringBootTest(classes = MemesApplication.class)
class KafkaMemeEventsTransactionTest {

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

    private KafkaMemeEvents events;
    private SpringOutbox outbox;

    @BeforeEach
    void freshOutbox() {
        // the same construction MemeOutboxConfig performs — only the broker is a mock, because a
        // broker outage is what half of these tests are about
        outbox = new SpringOutbox(dataSource,
                OutboxTable.named("meme_events_outbox"), clock, new KafkaMemeDispatch(kafka));
        events = new KafkaMemeEvents(outbox);
        jdbc.sql("DELETE FROM meme_events_outbox").update();
    }

    /**
     * What the republisher's pass does with the confirmations the producer's I/O thread recorded.
     *
     * <p>The mark used to happen inside that callback, and it is JDBC: a connection pool with
     * nothing free blocks there for thirty seconds and freezes every Kafka send this service makes,
     * which turns a struggling database into a broker outage. So the callback records and the pass
     * writes — and these tests have to run the pass to see the mark.
     */
    private void runThePassThatWritesTheMarks() {
        outbox.publisher().drainConfirmations();
    }

    private int outboxRows() {
        return jdbc.sql("SELECT COUNT(*) FROM meme_events_outbox").query(Integer.class).single();
    }

    private boolean published(String memeId) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM meme_events_outbox WHERE event_key = ?")
                .params(memeId).query(Boolean.class).single();
    }

    @Test
    @DisplayName("a rolled-back delete transaction leaves NO outbox row and publishes NO event")
    void rollback_discards_the_event() {
        tx.executeWithoutResult(status -> {
            events.memeDeleted("still-alive");
            status.setRollbackOnly();
        });

        verifyNoInteractions(kafka);
        assertEquals(0, outboxRows(),
                "the outbox row must share the fate of the transaction that wrote it");
    }

    @Test
    @DisplayName("inside a transaction the event waits for the commit, then goes out and is marked published")
    void commit_releases_the_event() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        tx.executeWithoutResult(status -> {
            events.memeDeleted("gone-for-good");
            verifyNoInteractions(kafka);   // not yet — the transaction could still roll back
        });

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertEquals("memes-events", sent.getValue().topic());
        assertEquals("gone-for-good", sent.getValue().key());
        assertTrue(sent.getValue().value().contains("MEME_DELETED"));
        assertFalse(published("gone-for-good"), "not from the producer's I/O thread");
        runThePassThatWritesTheMarks();
        assertTrue(published("gone-for-good"),
                "a CONFIRMED delivery must mark the outbox row, or the republisher would duplicate it");
    }

    @Test
    @DisplayName("a send the broker never confirms leaves the row unpublished — the republisher's cue")
    void unconfirmed_send_leaves_the_row_unpublished() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        // the failed after-commit attempt must not surface out of the (committed) delete either
        tx.executeWithoutResult(status -> events.memeDeleted("stranded"));

        verify(kafka).send(any(ProducerRecord.class));
        assertEquals(1, outboxRows(), "the event must survive the failed send");
        assertTrue(!published("stranded"),
                "an unconfirmed send must NOT mark the row — delivery has not happened");
    }

    @Test
    @DisplayName("a broker that never answers does not hold the deleting thread — the mark rides the ack callback")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void first_attempt_never_blocks_the_announcing_thread() {
        // The round-4 hazard: the first attempt used to Future.get(5s) PER EVENT on the
        // announcing thread — a purge of N memes with the broker down held the Kafka listener
        // thread N×5s, flirting with max.poll.interval and a rebalance. Now the send's future
        // may stay incomplete forever and memeDeleted must still return promptly; the row is
        // marked only when (and if) the broker's ack arrives.
        CompletableFuture pendingAck = new CompletableFuture();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(pendingAck);

        long before = System.nanoTime();
        tx.executeWithoutResult(status -> events.memeDeleted("slow-broker"));
        long heldMillis = java.time.Duration.ofNanos(System.nanoTime() - before).toMillis();

        assertTrue(heldMillis < 2_000,
                "the commit path must not wait for the broker (the old code held it 5s) — took "
                        + heldMillis + " ms");
        assertTrue(!published("slow-broker"), "no ack yet — the row must not be marked");

        pendingAck.complete(null);   // the ack finally arrives, on the producer's thread
        assertTrue(!published("slow-broker"), "recorded, not written — the callback may not do JDBC");
        runThePassThatWritesTheMarks();
        assertTrue(published("slow-broker"), "and the pass turns that record into the mark");
    }

    @Test
    @DisplayName("outside a transaction (no seam in sight) the event is published immediately")
    void outside_a_transaction_the_event_goes_out_now() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        events.memeDeleted("already-committed");

        verify(kafka).send(any(ProducerRecord.class));
        runThePassThatWritesTheMarks();
        assertTrue(published("already-committed"));
    }

    @Test
    @DisplayName("the outboxed event still carries the cid of the request that deleted the meme")
    void the_event_carries_the_correlation_id_of_the_deleting_request() {
        // The p4 guarantee, carried over to the outbox: the cid is captured from MDC at ANNOUNCE
        // time and STORED in the row, so the send (and any republish, hours later on a scheduler
        // thread with no MDC at all) still carries the trace of the request that deleted. The MDC
        // is cleared before the commit on purpose: a header that survives proves the eager
        // capture, not a lucky read at send time.
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        org.slf4j.MDC.put(CorrelationIdFilter.MDC_KEY, "cid-of-the-delete");
        try {
            tx.executeWithoutResult(status -> {
                events.memeDeleted("traced");
                org.slf4j.MDC.remove(CorrelationIdFilter.MDC_KEY);
            });
        } finally {
            org.slf4j.MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        org.apache.kafka.common.header.Header cid = sent.getValue().headers().lastHeader(KafkaTracing.HEADER);
        org.junit.jupiter.api.Assertions.assertNotNull(cid,
                "the correlation header must ride the MEME_DELETED record");
        assertEquals("cid-of-the-delete",
                new String(cid.value(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("cid-of-the-delete",
                jdbc.sql("SELECT cid FROM meme_events_outbox WHERE event_key = ?")
                        .params("traced").query(String.class).single(),
                "the cid must be IN the row — a republish rebuilds the header from there");
    }
}
