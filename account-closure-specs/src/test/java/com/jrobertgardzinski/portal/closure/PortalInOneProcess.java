package com.jrobertgardzinski.portal.closure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.collections.application.MarkUserItemsForErasure;
import com.jrobertgardzinski.collections.application.PurgeUserItems;
import com.jrobertgardzinski.collections.application.RestoreUserItems;
import com.jrobertgardzinski.collections.closure.CollectionsClosureParticipant;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.comments.closure.CommentsClosureParticipant;
import com.jrobertgardzinski.memes.application.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.application.MemeContentIndex;
import com.jrobertgardzinski.memes.application.MemeEvents;
import com.jrobertgardzinski.memes.application.PurgePolicyOverride;
import com.jrobertgardzinski.memes.application.PurgeUserContent;
import com.jrobertgardzinski.memes.application.RestoreUserContent;
import com.jrobertgardzinski.memes.application.TagRepository;
import com.jrobertgardzinski.memes.application.VoteRepository;
import com.jrobertgardzinski.memes.closure.MemesClosureParticipant;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.offboarding.application.Destination;
import com.jrobertgardzinski.offboarding.application.EventsRouter;
import com.jrobertgardzinski.offboarding.application.Source;
import com.jrobertgardzinski.offboarding.system.BeginOffboarding;
import com.jrobertgardzinski.offboarding.system.InMemorySagaStore;
import com.jrobertgardzinski.offboarding.system.RecordConfirmation;
import com.jrobertgardzinski.offboarding.system.SweepOverdue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * The whole portal, as four objects and a method call.
 *
 * <p>The orchestrator is the REAL {@link EventsRouter} — the same code the Kafka loop calls — and
 * the three participants are the real ones from {@code memes_account-closure},
 * {@code comments_account-closure} and {@code collections_account-closure}. What is fake is
 * everything below them (rows on the heap) and everything between them: where a deployment has a
 * broker, this class has {@link #deliver}, which hands each message to its reader and feeds the
 * answers straight back.
 *
 * <p>That substitution is the whole claim of these specs. The router already speaks in
 * {@link Source} and {@link Destination} rather than topic names, and the participants already
 * take a parsed {@link ClosureCommand}, so neither had to be adapted to run this way — which is
 * the difference between a portal that CAN be assembled in one process and a portal that has been
 * rewritten until it could.
 *
 * <p>One thing is faithfully NOT modelled: a message may be withheld from a participant
 * ({@link #silence}), but nothing here reorders, duplicates or drops messages at random. This is
 * the choreography's story; the broker's is the integration tests' and the live stack's.
 */
final class PortalInOneProcess {

    static final String MEMES = "memes";
    static final String COMMENTS = "comments";
    static final String COLLECTIONS = "collections";

    private static final Duration PURGE_TIMEOUT = Duration.ofMinutes(2);

    private final ObjectMapper mapper = new ObjectMapper();

    final HeapMemes memes = new HeapMemes();
    final HeapComments comments = new HeapComments();
    final HeapFavourites favourites = new HeapFavourites();

    private final InMemorySagaStore sagas = new InMemorySagaStore();
    private final EventsRouter router;
    private final MemesClosureParticipant memesParticipant;
    private final CommentsClosureParticipant commentsParticipant;
    private final CollectionsClosureParticipant collectionsParticipant;

    /** Participants this run keeps a message from — the one failure this file stages. */
    private final Set<String> silenced = new HashSet<>();

    /** Everything the portal has said to security. The verdict the person is owed is in here. */
    private final List<JsonNode> toSecurity = new ArrayList<>();

    /** What the portal has published and nobody has carried yet — the transport, as a queue. */
    private final List<EventsRouter.Outgoing> inFlight = new ArrayList<>();

    private Instant now = Instant.parse("2026-09-24T12:00:00Z");

    PortalInOneProcess() {
        Set<String> participants = Set.of(MEMES, COMMENTS, COLLECTIONS);
        router = new EventsRouter(
                new BeginOffboarding(sagas, participants),
                new RecordConfirmation(sagas, participants),
                new SweepOverdue(sagas, PURGE_TIMEOUT),
                mapper, windUpClock());

        memesParticipant = new MemesClosureParticipant(
                new MarkUserContentForErasure(memes, windUpClock()),
                new RestoreUserContent(memes),
                new PurgeUserContent(memes, memes, mock(VoteRepository.class),
                        mock(MemeContentIndex.class), mock(TagRepository.class),
                        mock(MemeEvents.class), mock(PurgePolicyOverride.class),
                        new com.jrobertgardzinski.memes.config.PurgeRule.Delete()),
                // no outbox in here: the confirmation is what deliver() sends back
                (sagaId, leaver, reserved) -> { },
                Observations.silent(), Runnable::run);

        commentsParticipant = new CommentsClosureParticipant(
                new MarkUserCommentsForErasure(comments, windUpClock()),
                new RestoreUserComments(comments),
                new PurgeUserComments(comments, comments, mock(CommentVotes.class),
                        new com.jrobertgardzinski.comments.config.PurgeRule.Delete()),
                (sagaId, leaver, reserved) -> { },
                Observations.silent(), Runnable::run);

        collectionsParticipant = new CollectionsClosureParticipant(
                new MarkUserItemsForErasure(favourites, windUpClock()),
                new RestoreUserItems(favourites),
                new PurgeUserItems(favourites),
                Observations.silent());
    }

    /** A clock the scenarios wind forward by reassigning {@link #now}. */
    private Clock windUpClock() {
        return new Clock() {
            @Override
            public Instant instant() {
                return now;
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
    }

    void silence(String participant) {
        silenced.add(participant);
    }

    /** Security states the fact that opens the case; everything that follows follows from it. */
    void securityAnnouncesClosureOf(String email, String initiatedBy, String policyJson) {
        String fact = "{\"id\":\"" + UUID.nameUUIDFromBytes(("fact:" + email).getBytes())
                + "\",\"type\":\"" + ClosureMessages.ACCOUNT_DELETION_REQUESTED + "\","
                + "\"email\":\"" + email + "\","
                + "\"" + ClosureMessages.Field.INITIATED_BY + "\":\"" + initiatedBy + "\""
                + (policyJson == null ? "" : ",\"" + ClosureMessages.Field.POLICY + "\":" + policyJson)
                + ",\"version\":1}";
        inFlight.addAll(router.handle(Source.SECURITY, fact));
    }

    /**
     * The portal waits out the silence and capitulates. Each DELIVERED re-command buys the silent
     * participant another whole timeout, so exhausting the budget takes one deadline per retry
     * plus the one that finally gives up — the same arithmetic the orchestrator's own specs use.
     */
    void givesUpWaiting() {
        for (int attempt = 0; attempt <= SweepOverdue.DEFAULT_MAX_RETRIES; attempt++) {
            now = now.plus(PURGE_TIMEOUT).plusSeconds(1);
            List<EventsRouter.Outgoing> swept = router.sweepOverdue();
            swept.stream().map(EventsRouter.Outgoing::countsRetryFor).filter(Objects::nonNull)
                    .forEach(charge -> sagas.retryDelivered(charge.sagaId(), charge.retriesSoFar(), now));
            inFlight.addAll(swept);
            everyPartAnswers();
        }
    }

    /**
     * The transport, as a method. A message for the participants is read by each of them that is
     * listening; a confirmation goes back into the router, and whatever THAT answers joins the
     * queue — which is how the last confirmation ends up commanding the erasure without anybody
     * scheduling anything. {@code everyPartAnswers} drains the queue until the portal has nothing
     * left to say, so a scenario's step says "the transport did its job" and means it.
     */
    void everyPartAnswers() {
        while (!inFlight.isEmpty()) {
            List<EventsRouter.Outgoing> batch = List.copyOf(inFlight);
            inFlight.clear();
            deliver(batch);
        }
    }

    private void deliver(List<EventsRouter.Outgoing> messages) {
        for (EventsRouter.Outgoing message : messages) {
            if (message.destination() == Destination.SECURITY) {
                toSecurity.add(read(message.payload()));
                continue;
            }
            JsonNode command = read(message.payload());
            for (String participant : List.of(MEMES, COMMENTS, COLLECTIONS)) {
                if (silenced.contains(participant)) {
                    continue;   // the message never arrives; nothing answers for it
                }
                answerOf(participant, command)
                        .ifPresent(confirmation -> inFlight.addAll(router.handle(
                                Source.participant(participant), confirmation)));
            }
        }
    }

    /** One participant's whole part in one command: the decision, and what it says back. */
    private Optional<String> answerOf(String participant, JsonNode command) {
        String type = command.path(ClosureMessages.Field.TYPE).asText();
        String email = command.path(ClosureMessages.Field.EMAIL).asText();
        String sagaId = command.path(ClosureMessages.Field.SAGA_ID).asText();
        String initiatedBy = command.path(ClosureMessages.Field.INITIATED_BY).asText();
        JsonNode rule = command.path(ClosureMessages.Field.POLICY).path(participant);
        ClosureCommand parsed = new ClosureCommand(type, sagaId, email, initiatedBy,
                rule.isMissingNode() ? Optional.empty() : Optional.of(rule.asText()));

        boolean reserved = switch (participant) {
            case MEMES -> memesParticipant.handle(parsed)
                    instanceof com.jrobertgardzinski.memes.closure.ClosureOutcome.Reserved;
            case COMMENTS -> commentsParticipant.handle(parsed)
                    instanceof com.jrobertgardzinski.comments.closure.ClosureOutcome.Reserved;
            case COLLECTIONS -> collectionsParticipant.handle(parsed)
                    instanceof com.jrobertgardzinski.collections.closure.ClosureOutcome.Reserved;
            default -> throw new IllegalStateException("no such participant: " + participant);
        };
        // only the reversible step is answered: the closure and the compensation END the case
        return reserved
                ? Optional.of("{\"type\":\"" + ClosureMessages.USER_CONTENT_PURGED + "\","
                + "\"email\":\"" + email + "\",\"sagaId\":\"" + sagaId + "\",\"version\":1}")
                : Optional.empty();
    }

    /** Everything the portal has told security, in the order it said it. */
    List<JsonNode> saidToSecurity() {
        return List.copyOf(toSecurity);
    }

    private JsonNode read(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (Exception unreadable) {
            throw new IllegalStateException("the portal published something unreadable: " + payload,
                    unreadable);
        }
    }
}
