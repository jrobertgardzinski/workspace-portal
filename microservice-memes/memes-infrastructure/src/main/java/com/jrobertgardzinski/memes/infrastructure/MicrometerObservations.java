package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.memes.domain.Observation;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The one class in this service that knows what a metric is called.
 *
 * <p>Everything above it states facts ({@link Observation}); here they are given this month's
 * spelling — {@code memes_erasure_backlog}, {@code memes_kafka_records_dropped_total} — the shape
 * Prometheus wants and the labels an alert matches on. Swapping Micrometer for whatever comes next
 * is rewriting this file and nothing else, which is the entire reason the port exists.
 *
 * <p>Two deliberate translations, and neither is obvious from the fact alone:
 * <ul>
 *   <li>the backlog is a GAUGE. The question is "how many obligations right now", so it has to fall
 *       back to zero on its own the moment a closure finally lands — which is also why the use case
 *       states it on every pass, zero included.</li>
 *   <li>a dropped command is a COUNTER, labelled by topic. It never falls; one increment is one
 *       saga command this service will never carry out, and an operator wants the total since the
 *       process started, not a number that quietly forgets.</li>
 * </ul>
 *
 * <p>A backlog that could NOT be read states nothing at all, and that is the honest behaviour
 * rather than a gap: the gauge keeps its last value, where reporting zero would turn a failed
 * database read into "the backlog is clear".
 */
@Component
@ConditionalOnProperty(name = "memes.observability-enabled", havingValue = "true", matchIfMissing = true)
class MicrometerObservations implements Observations<Observation> {

    private final AtomicLong erasureBacklog = new AtomicLong();
    private final MeterRegistry meters;

    MicrometerObservations(MeterRegistry meters) {
        this.meters = meters;
        meters.gauge("memes.erasure.backlog", erasureBacklog, AtomicLong::get);
    }

    @Override
    public void record(Observation observation) {
        switch (observation) {
            case Observation.ErasureBacklog backlog -> erasureBacklog.set(backlog.marked());
            case Observation.SagaCommandDropped dropped ->
                    meters.counter("memes.kafka.records.dropped", "topic", dropped.topic()).increment();
            // also a COUNTER, and for the same reason: one increment is one deletion this service
            // answered with an empty confirmation. It is the alarm for a rename that lost its race
            // with a purge — a steady zero is the healthy reading
            case Observation.PurgeReservedNothing ignored ->
                    meters.counter("memes.saga.purge.reserved.nothing").increment();
        }
    }
}
