package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.outbox.Dispatch;
import com.jrobertgardzinski.outbox.DispatchOutcome;
import com.jrobertgardzinski.outbox.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;

/**
 * The outbox's send, over this service's {@code KafkaTemplate} — the ONE thing the shared outbox
 * library cannot do for itself, because it has no broker client and never will (it would force a
 * Kafka version on every consumer of the library).
 *
 * <p>It is the whole Kafka side of the durability mechanism, so both halves of the
 * {@link Dispatch} contract are honoured here quite deliberately:
 *
 * <ol>
 *   <li><strong>The calling thread does not wait for the acknowledgement.</strong>
 *       {@code send()} hands the record over and the outcome is reported from the completion
 *       callback, which runs on the PRODUCER'S I/O thread. That is what lets the library's first
 *       attempt run on a request thread — or, far worse, on the Kafka LISTENER thread that
 *       {@link PurgeCommandsListener} uses to drive a purge of N memes. A blocking send there
 *       would cost that thread N&times;(broker patience) with the broker down, overrun
 *       {@code max.poll.interval.ms} (300s) and turn a broker outage into a consumer-group
 *       rebalance. When someone DOES need to wait it is the library's republisher leg that waits,
 *       on a scheduler thread where waiting is free.
 *       <p>"Does not wait" is precise, not absolute: {@code KafkaTemplate.send} is asynchronous
 *       only once the record reaches the accumulator, and getting there blocks on the metadata
 *       fetch and on buffer space for up to {@code max.block.ms} — Kafka's own default being 60s,
 *       which would make the paragraph above false. That dial is pinned to 5s in
 *       {@code application.properties} and asserted by {@link KafkaProducerClocksTest}; the
 *       library cannot see producer properties, so this promise stays this service's to keep.</li>
 *   <li><strong>{@code confirmed()} means the BROKER acknowledged</strong> — it is the completion
 *       of the send future with no failure, nothing earlier. The library marks the row published on
 *       that word alone and then stops guaranteeing the event, so an optimistic confirmation here
 *       would be a lost event with a green log. {@link MemeEventsOutboxTest}'s
 *       {@code unconfirmed_send_leaves_the_row_unpublished} is the test that keeps this honest from
 *       the outside.</li>
 * </ol>
 */
final class KafkaMemeDispatch implements Dispatch {

    private final KafkaTemplate<String, String> kafka;

    KafkaMemeDispatch(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void send(OutboxEvent event, DispatchOutcome outcome) {
        kafka.send(toRecord(event)).whenComplete((ack, notConfirmed) -> {
            if (notConfirmed != null) {
                outcome.failed(notConfirmed);
                return;
            }
            outcome.confirmed();
        });
        // A synchronous refusal (producer closed, buffer full, serialisation) throws out of send()
        // instead of completing the future; the library treats the throw as a failure, which is
        // the same conclusion — the row stays and the republisher retries — so it is not caught
        // here. Catching it would only duplicate the library's log line.
    }

    /**
     * The row rebuilt into the record the broker gets. Every field comes from the STORED event, the
     * correlation id included: a republication happens hours later on a scheduler thread whose MDC
     * is empty, so reading the ambient cid there would yield nothing — or worse, someone else's trace.
     * Since round 11 that covers the saga's confirmations too, which used to be stamped from the MDC
     * by a helper this class made redundant.
     */
    static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.topic(), event.key(), event.payload());
        if (event.cid() != null) {
            record.headers().add(KafkaTracing.HEADER, event.cid().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
