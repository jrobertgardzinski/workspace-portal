package com.jrobertgardzinski.memes.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
import com.jrobertgardzinski.memes.closure.MemesClosureParticipant;
import com.jrobertgardzinski.memes.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * How the account-closure commands REACH this service: a Kafka record, a correlation id in a
 * header, JSON on the wire. What the service then does about them is
 * {@link MemesClosureParticipant}, over in memes_account-closure, which knows none of that —
 * this class exists to make sure it never has to.
 *
 * <p>So the split is: everything here is about the carrier (which topic, which group, which
 * header, what a malformed body means), and everything there is about the closure (which use
 * case a command means, which rule applies, what is confirmed and what is not). A monolith
 * assembly replaces this file and keeps that one.
 *
 * <p>The three guarantees around the carrier stay here too, because they are the carrier's:
 * <ul>
 *   <li>a command is no longer lost to a one-second hiccup — {@link SagaRetryBudget} retries it
 *       with backoff for a budget derived from the orchestrator's own timeline, then drops it
 *       loudly and counted instead of silently in a millisecond;</li>
 *   <li>a stalled listener loop no longer hides behind a green process —
 *       {@link SagaListenersHealth} turns readiness red;</li>
 *   <li>the confirmation is no longer a bare fire-and-forget send — {@link PurgeConfirmations}
 *       writes it into the outbox, in the SAME transaction as the step it confirms. That
 *       transaction is this class's contribution to the participant: it hands it a
 *       {@code TransactionTemplate} wearing the participant's own name for "all of it or none of
 *       it", and the use case's transactional decorator joins it (Spring's default propagation),
 *       exactly as {@code MemesEventsListener} does for the cascade in microservice-comments.</li>
 * </ul>
 *
 * <p>Enabled only where a broker exists (compose sets KAFKA_ENABLED) — tests exercise the use
 * cases directly and the whole loop runs in the workspace smoke test.
 */
@Component
@ConditionalOnProperty(name = "memes.kafka-enabled", havingValue = "true")
class PurgeCommandsListener {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeCommandsListener.class);

    private final MemesClosureParticipant participant;
    private final ObjectMapper mapper;

    PurgeCommandsListener(MarkUserContentForErasure markForErasure,
                          RestoreUserContent restoreUserContent,
                          PurgeUserContent purgeUserContent, PurgeConfirmations confirmations,
                          Observations<Observation> observations,
                          ObjectMapper mapper, TransactionTemplate tx) {
        this.mapper = mapper;
        this.participant = new MemesClosureParticipant(markForErasure, restoreUserContent,
                purgeUserContent, confirmations::confirm, observations,
                step -> tx.executeWithoutResult(status -> step.run()));
    }

    /**
     * The container id is spelled out (the group id is unchanged, {@code memes} — with a group id
     * present, the id names only the container): it is what {@link SagaListenersHealth} prints under
     * {@code /actuator/health}, and "memes-purge-commands is not polling" is an answer, while
     * "org.springframework.kafka.KafkaListenerEndpointContainer#0" is a riddle.
     */
    @KafkaListener(id = "memes-purge-commands", topics = "content-commands", groupId = "memes")
    void receive(String payload,
                 @Header(name = KafkaTracing.HEADER, required = false) String cid) throws Exception {
        if (cid != null) {
            MDC.put("cid", cid);   // continue the trace the deletion request started in security
        }
        try {
            read(payload).ifPresent(participant::handle);
        } finally {
            MDC.remove("cid");
        }
    }

    /**
     * The wire, read once, here and nowhere else. An unreadable body is the carrier's problem and
     * dies here; every field the closure cares about is named by the agreement, so the participant
     * never meets a {@link JsonNode}.
     */
    private Optional<ClosureCommand> read(String payload) {
        JsonNode command;
        try {
            command = mapper.readTree(payload);
        } catch (Exception malformed) {
            // NOT the payload itself: a purge command carries the leaver's e-mail, and even a
            // malformed one may — PII stays out of the logs, the size is enough to investigate
            LOG.warn("dropping a malformed command ({} chars, not valid JSON)",
                    payload == null ? 0 : payload.length());
            return Optional.empty();
        }
        JsonNode rule = command.path(ClosureMessages.Field.POLICY).path("memes");
        return Optional.of(new ClosureCommand(
                command.path(ClosureMessages.Field.TYPE).asText(),
                command.path(ClosureMessages.Field.SAGA_ID).asText(),
                ClosureCommand.userIdOf(command.path(ClosureMessages.Field.USER_ID).asText(null)).orElse(null),
                command.path(ClosureMessages.Field.INITIATED_BY).asText(),
                rule.isMissingNode() ? Optional.empty() : Optional.of(rule.asText())));
    }
}
