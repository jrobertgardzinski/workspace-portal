package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
import com.jrobertgardzinski.outbox.OutboxRepublisher;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The saga's confirmation, now that it rides the outbox — tested against the REAL
 * {@code meme_events_outbox} table and the REAL transaction manager, because every property worth
 * having here is a property of a row and a commit.
 *
 * <p>What the bare {@code kafka.send()} could not promise, and what these tests are about: Spring
 * Kafka commits the command's offset as soon as the listener returns, so a send still sitting in the
 * producer's accumulator when the process dies takes the confirmation with it — the leaver's memes
 * marked, the orchestrator none the wiser, three re-commands later the saga compensates. Since the
 * mark is reversible that no longer costs the leaver their content, but it still costs them a
 * deletion that silently did not happen. The outbox turns that into a row that
 * outlives the process, and the republisher into a delivery that outlives the outage.
 */
@Epic("Saga")
@Feature("Purge confirmation")
@Story("Outbox durability")
@SpringBootTest(classes = MemesApplication.class)
class PurgeConfirmationOutboxTest {

    private static final UserId LEAVER = UserId.of("0b7c1c2e-5d3a-4f1b-9e8d-6a5b4c3d2e1f");
    private static final String SAGA = "5c1c7e3d-9b41-4b32-8f0a-7a3f2d1e5b90";
    private static final String COMMAND = "{\"type\":\"PURGE_USER_CONTENT\",\"userId\":\"" + LEAVER
            + "\",\"sagaId\":\"" + SAGA + "\"}";

    @Autowired
    TransactionTemplate tx;

    @Autowired
    DataSource dataSource;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final PurgeUserContent purgeUserContent = mock(PurgeUserContent.class);
    private final MarkUserContentForErasure markForErasure = mock(MarkUserContentForErasure.class);

    private SpringOutbox springOutbox;
    private OutboxRepublisher republisher;
    private PurgeCommandsListener listener;

    @BeforeEach
    void freshOutbox() {
        // exactly MemeOutboxConfig's and SagaParticipantConfig's wiring, with a mock broker
        springOutbox = new SpringOutbox(dataSource, OutboxTable.named("meme_events_outbox"), clock,
                new KafkaMemeDispatch(kafka));
        republisher = MemeOutboxConfig.republisher(springOutbox, 24);
        listener = new PurgeCommandsListener(markForErasure, mock(RestoreUserContent.class),
                purgeUserContent, new PurgeConfirmations(springOutbox, mapper),
                Observations.silent(), mapper, tx);
        jdbc.sql("DELETE FROM meme_events_outbox").update();
    }

    private int confirmationRows() {
        return jdbc.sql("SELECT COUNT(*) FROM meme_events_outbox WHERE event_type = ?")
                .params(PurgeConfirmations.USER_CONTENT_PURGED).query(Integer.class).single();
    }

    private boolean published() {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM meme_events_outbox WHERE event_key = ?")
                .params(SAGA).query(Boolean.class).single();
    }

    @Test
    @DisplayName("a send the broker never confirms leaves the confirmation OWED — and the republisher pays it")
    void an_unconfirmed_confirmation_is_kept_and_re_sent() throws Exception {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        listener.receive(COMMAND, "cid-of-the-deletion");

        // the command is the MARK, so the mark is what ran: the erasure waits for the closure
        verify(markForErasure).execute(LEAVER);
        verifyNoInteractions(purgeUserContent);
        ArgumentCaptor<ProducerRecord<String, String>> firstTry =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(firstTry.capture());
        assertFalse(published(), "an unconfirmed send is not a delivery: the row keeps the obligation");

        // the broker comes back and the row comes of age
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        jdbc.sql("UPDATE meme_events_outbox SET created_at = DATEADD('SECOND', -60, created_at), published_at = DATEADD('SECOND', -60, published_at)").update();
        republisher.runOnce();

        ArgumentCaptor<ProducerRecord<String, String>> redelivered =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(redelivered.capture());
        assertEquals(firstTry.getValue().value(), redelivered.getValue().value(),
                "the redelivery is the SAME confirmation, byte for byte — the row IS the record");
        assertTrue(published(), "and only a confirmed delivery marks it");

        republisher.runOnce();
        verifyNoMoreInteractions(kafka);   // delivered means done
    }

    @Test
    @DisplayName("the confirmation goes out on memes-events, keyed by the saga, carrying the trace")
    void the_confirmation_is_addressed_the_way_the_orchestrator_expects() throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        listener.receive(COMMAND, "cid-of-the-deletion");

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        ProducerRecord<String, String> confirmation = sent.getValue();

        assertEquals(KafkaMemeEvents.TOPIC, confirmation.topic(),
                "microservice-offboarding subscribes here — see PurgeConfirmationTopicTest");
        assertEquals(SAGA, confirmation.key(),
                "keyed by the saga run, not by the address: event_key is varchar(64) and an address"
                        + " may be 254 characters — and a key is broker-visible metadata");
        assertNotNull(confirmation.headers().lastHeader(KafkaTracing.HEADER),
                "the cid rides along, stored in the row so even a republication hours later has it");
        assertEquals("cid-of-the-deletion", new String(
                confirmation.headers().lastHeader(KafkaTracing.HEADER).value(), StandardCharsets.UTF_8));

        JsonNode payload = mapper.readTree(confirmation.value());
        assertEquals("USER_CONTENT_PURGED", payload.path("type").asText());
        assertEquals(SAGA, payload.path("sagaId").asText());
        assertEquals(LEAVER.toString(), payload.path("userId").asText(), "the orchestrator matches on the id");
        assertEquals(1, payload.path("version").asInt(), "envelope version 1 (ADR 0004)");
    }

    @Test
    @DisplayName("a rolled-back purge leaves NO confirmation — the row shares the erasure's fate")
    void a_rolled_back_purge_confirms_nothing() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        // the listener JOINS an outer transaction (Spring's default propagation), so rolling that one
        // back is the honest way to stage "the erasure did not survive" from the outside
        tx.executeWithoutResult(status -> {
            try {
                listener.receive(COMMAND, null);
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
            status.setRollbackOnly();
        });

        assertEquals(0, confirmationRows(),
                "a confirmation that outlived its purge would tell the orchestrator to delete an"
                        + " account whose content is still there");
        verifyNoMoreInteractions(kafka);   // the parked first attempt is dropped with the rollback
    }

    @Test
    @DisplayName("a mark that throws writes nothing at all and lets the failure out to the container")
    void a_failing_purge_writes_nothing() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("no database"))
                .when(markForErasure).execute(LEAVER);

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> listener.receive(COMMAND, null));

        assertEquals(0, confirmationRows());
        verifyNoMoreInteractions(kafka);
    }
}
