package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.outbox.OutboxDials;
import com.jrobertgardzinski.outbox.OutboxRepublisher;
import com.jrobertgardzinski.outbox.OutboxTable;
import com.jrobertgardzinski.outbox.RepublisherSettings;
import com.jrobertgardzinski.outbox.spring.ScheduledOutboxRepublisher;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Wires the shared outbox library into this service: the store, the send, and the schedule that
 * makes the guarantee true. Everything the library deliberately refused to decide for its host is
 * decided here.
 *
 * <p><strong>Why the whole thing hangs off {@code memes.kafka-enabled}.</strong> Without a broker
 * there is nothing to be durable towards: {@link NoopMemeEvents} takes the port, no outbox row is
 * ever written, and — the part that matters — no scheduler thread wakes up every 15 seconds in a
 * test JVM to poll a table. That is also why {@code @EnableScheduling} sits HERE and not in the
 * library: switching on a whole framework subsystem in someone else's application is not a
 * library's business, and this configuration is the one place that knows scheduling is wanted.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "memes.kafka-enabled", havingValue = "true")
class MemeOutboxConfig {

    /**
     * The retention dial as the OPERATOR spells it (env {@code MEMES_OUTBOX_RETENTION_HOURS}). The
     * library validates the value but cannot know this string — it owns no configuration namespace —
     * so the name travels with the value into {@link OutboxDials#retentionHours}, which is what puts
     * it in the message the operator reads when they set it to zero.
     */
    static final String RETENTION_PROPERTY = "memes.outbox.retention-hours";

    /**
     * The table V5 created and V6 widened. The library takes the NAME as a parameter and ships the
     * shape ({@link OutboxTable#ddl()}) instead of a migration, because a library has no right to a
     * version number in this service's Flyway sequence. The two agree column for column, index name
     * included — checked when the library was extracted, which is why round 10 needed no V7.
     */
    @Bean
    SpringOutbox memeEventsOutbox(DataSource dataSource, Clock clock,
                                  KafkaTemplate<String, String> kafka) {
        return new SpringOutbox(dataSource, OutboxTable.named("meme_events_outbox"), clock,
                new KafkaMemeDispatch(kafka));
    }

    @Bean
    MemeEvents memeEvents(SpringOutbox memeEventsOutbox) {
        return new KafkaMemeEvents(memeEventsOutbox);
    }

    @Bean
    ScheduledOutboxRepublisher memeEventsOutboxRepublisher(
            SpringOutbox memeEventsOutbox,
            @Value("${memes.outbox.retention-hours:24}") long retentionHours) {
        return new ScheduledOutboxRepublisher(republisher(memeEventsOutbox, retentionHours));
    }

    /**
     * The republisher this service runs, as a plain factory rather than only a {@code @Bean} body —
     * so a test can pin what happens to a BROKEN dial without booting a context that refuses to
     * start (and without going through a {@code @Configuration} proxy, which would hand back the
     * singleton and ignore the argument).
     *
     * <p>{@link RepublisherSettings#defaults} carries the numbers this service ran on before the
     * extraction: 30s minimum age (below the producer's own 30s delivery timeout a re-send would
     * routinely duplicate an attempt still in flight), 5s confirmation patience — the same 5s as
     * {@code max.block.ms}, one "how long do we wait for a broker" number for the whole service —
     * and 500-row retention batches, at most 4 per pass.
     */
    static OutboxRepublisher republisher(SpringOutbox outbox, long retentionHours) {
        return new OutboxRepublisher(outbox.outbox(), outbox.publisher(),
                RepublisherSettings.defaults(
                        OutboxDials.retentionHours(RETENTION_PROPERTY, retentionHours)));
    }
}
