package com.jrobertgardzinski.portal.closure;

import com.jrobertgardzinski.identity.UserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureConfirmation;
import com.jrobertgardzinski.closure.ClosureConfirmations;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.closure.ClosureOutcome;
import com.jrobertgardzinski.closure.ClosureParticipant;
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
import com.jrobertgardzinski.portal.world.FakeFavourites;
import com.jrobertgardzinski.portal.world.Identities;
import com.jrobertgardzinski.portal.deletion.DeletionInOneProcess;
import com.jrobertgardzinski.portal.world.Portal;
import com.jrobertgardzinski.portal.world.FakeComments;
import com.jrobertgardzinski.portal.world.FakeMemes;
import com.jrobertgardzinski.offboarding.application.Destination;
import com.jrobertgardzinski.offboarding.application.EventsRouter;
import com.jrobertgardzinski.offboarding.application.Source;
import com.jrobertgardzinski.offboarding.system.BeginOffboarding;
import com.jrobertgardzinski.offboarding.system.FakeSagaStore;
import com.jrobertgardzinski.offboarding.system.RecordConfirmation;
import com.jrobertgardzinski.offboarding.system.SweepOverdue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * The account-closure saga in one process: the real {@link EventsRouter}, the real three
 * participants over {@link Portal}'s rows, and {@link #deliver} in place of the broker. Nothing
 * is reordered, duplicated or dropped at random; {@link #silence} is the only failure staged.
 *
 * <p>This class is the saga's BUS. The portal it drives is {@link Portal}, and the deletion
 * cascade acts on the SAME rows, so this class holds that bus too ({@link #cascade}) rather than
 * letting a second {@code new Portal()} pretend the two protocols meet nowhere. There is no
 * orchestrator over there to share — only the rows.
 */
public final class ClosureInOneProcess {

    static final String MEMES = "memes";
    static final String COMMENTS = "comments";
    static final String COLLECTIONS = "collections";

    private static final Duration PURGE_TIMEOUT = Duration.ofMinutes(2);

    private final ObjectMapper mapper = new ObjectMapper();

    private final Portal world = new Portal();

    /**
     * The OTHER protocol, over the same rows. {@code PurgeUserContent} announces every meme it
     * destroys, and in the deployed stack that announcement is a {@code MEME_DELETED} on the
     * cascade's topic — so one account closure starts N cascades, after the pivot, with nobody
     * orchestrating them and nothing to compensate with. A mock here made that seam unstatable:
     * the rule {@code anonymise} and the rule {@code delete} looked identical to everything
     * outside the memes part.
     *
     * <p>It is NOT drained by {@link #everyPartAnswers}. The saga is finished the moment the last
     * confirmation lands; the cascade it set off is still in the air, and a scenario that wants
     * it delivered says so ({@link #cascadeReachesEveryPart}).
     */
    private final DeletionInOneProcess cascade = new DeletionInOneProcess(world);

    // the steps read the portal through this class; the rows themselves are the world's
    final com.jrobertgardzinski.memes.application.FakeVoteRepository memeVotes = world.memeVotes;
    final com.jrobertgardzinski.comments.application.FakeCommentVotes commentVotes = world.commentVotes;
    final FakeMemes memes = world.memes;
    final FakeComments comments = world.comments;
    final FakeFavourites favourites = world.favourites;

    private final FakeSagaStore sagas = new FakeSagaStore();
    private final EventsRouter router;
    private final MemesClosureParticipant memesParticipant;
    private final CommentsClosureParticipant commentsParticipant;
    private final CollectionsClosureParticipant collectionsParticipant;

    private final Set<String> silenced = new HashSet<>();

    private final List<JsonNode> toSecurity = new ArrayList<>();

    /** The reservation each part last confirmed — the only count that ever leaves the portal. */
    private final Map<String, Integer> confirmedBy = new LinkedHashMap<>();

    private final List<EventsRouter.Outgoing> inFlight = new ArrayList<>();

    ClosureInOneProcess() {
        Set<String> participants = Set.of(MEMES, COMMENTS, COLLECTIONS);
        router = new EventsRouter(
                new BeginOffboarding(sagas, participants),
                new RecordConfirmation(sagas, participants),
                new SweepOverdue(sagas, PURGE_TIMEOUT),
                mapper, world.clock());

        // no outbox in here: the confirmation is what deliver() sends back
        ClosureConfirmations nothingToAnnounce = (sagaId, leaver, reserved) -> { };
        memesParticipant = world.memesClosure(nothingToAnnounce, cascade.memeEvents());
        commentsParticipant = world.commentsClosure(nothingToAnnounce, cascade.commentEvents());
        collectionsParticipant = world.collectionsClosure();
    }

    /** Every hop of every cascade this closure set off hears it — which nobody waits for. */
    void cascadeReachesEveryPart() {
        cascade.everyHopAnswers();
    }

    /** Somebody takes a meme down while the saga is mid-flight. */
    void takeDown(String memeId) {
        cascade.takeDown(memeId);
    }

    /** Was anything announced at all — the difference between a meme deleted and one anonymised. */
    boolean cascadeIsInFlight() {
        return cascade.somethingIsInFlight();
    }

    void silence(String participant) {
        silenced.add(participant);
    }

    void securityAnnouncesClosureOf(String email, String initiatedBy, String policyJson) {
        String fact = "{\"id\":\"" + UUID.nameUUIDFromBytes(("fact:" + email).getBytes())
                + "\",\"type\":\"" + ClosureMessages.ACCOUNT_DELETION_REQUESTED + "\","
                + "\"email\":\"" + email + "\","
                + "\"" + ClosureMessages.Field.USER_ID + "\":\"" + Identities.idOf(email) + "\","
                + "\"" + ClosureMessages.Field.INITIATED_BY + "\":\"" + initiatedBy + "\""
                + (policyJson == null ? "" : ",\"" + ClosureMessages.Field.POLICY + "\":" + policyJson)
                + ",\"version\":1}";
        inFlight.addAll(router.handle(Source.SECURITY, fact));
    }

    /** Each delivered re-command buys the silent part another timeout; spend the budget, then the one that gives up. */
    void givesUpWaiting() {
        for (int attempt = 0; attempt <= SweepOverdue.DEFAULT_MAX_RETRIES; attempt++) {
            waitsAndAsksAgain();
        }
    }

    /**
     * One timeout spent and one re-command sent — a retry, not a capitulation. What it buys a
     * scenario is a GAP: between the mark and the closure there is now a moment the specs can put
     * something in, which is where the two protocols actually collide.
     */
    void waitsAndAsksAgain() {
        world.windForward(PURGE_TIMEOUT.plusSeconds(1));
        Instant now = world.clock().instant();
        List<EventsRouter.Outgoing> swept = router.sweepOverdue();
        swept.stream().map(EventsRouter.Outgoing::countsRetryFor).filter(Objects::nonNull)
                .forEach(charge -> sagas.retryDelivered(charge.sagaId(), charge.retriesSoFar(), now));
        inFlight.addAll(swept);
        everyPartAnswers();
    }

    /** The part comes back — a restarted consumer, which is how a silence ends in production. */
    void hearsAgain(String participant) {
        silenced.remove(participant);
    }

    /** Drains the queue: confirmations go back into the router and whatever it answers joins the queue. */
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

    /** Built from the same record the deployed participants use, so this transport cannot carry a different message. */
    private Optional<String> answerOf(String participant, JsonNode command) {
        String type = command.path(ClosureMessages.Field.TYPE).asText();
        String sagaId = command.path(ClosureMessages.Field.SAGA_ID).asText();
        String initiatedBy = command.path(ClosureMessages.Field.INITIATED_BY).asText();
        JsonNode rule = command.path(ClosureMessages.Field.POLICY).path(participant);
        UserId leaver = ClosureCommand.userIdOf(command.path(ClosureMessages.Field.USER_ID).asText(null)).orElse(null);
        ClosureCommand parsed = new ClosureCommand(type, sagaId, leaver, initiatedBy,
                rule.isMissingNode() ? Optional.empty() : Optional.of(rule.asText()));

        // one vocabulary for all three since 28.09.2026: this used to be three fully-qualified
        // ClosureOutcome.Reserved patterns, one per service, in a switch that existed only because
        // the three said the same thing in three types
        ClosureParticipant axis = switch (participant) {
            case MEMES -> memesParticipant;
            case COMMENTS -> commentsParticipant;
            case COLLECTIONS -> collectionsParticipant;
            default -> throw new IllegalStateException("no such participant: " + participant);
        };
        // the count it reserved, or -1 for "this was not the reversible step"
        int reserved = axis.handle(parsed) instanceof ClosureOutcome.Reserved(int rows) ? rows : -1;
        if (reserved < 0) {
            return Optional.empty();
        }
        // the FIRST one: a re-commanded MARK finds everything already reserved and confirms 0,
        // which is idempotence working, not the count the saga was advanced on
        confirmedBy.putIfAbsent(participant, reserved);
        try {
            return Optional.of(mapper.writeValueAsString(
                    new ClosureConfirmation(sagaId, leaver, reserved).fields()));
        } catch (Exception impossible) {
            throw new IllegalStateException("could not serialise a confirmation", impossible);
        }
    }

    /** What this part told the orchestrator it had reserved, the first time it answered. */
    int confirmedBy(String participant) {
        Integer said = confirmedBy.get(participant);
        if (said == null) {
            throw new IllegalStateException(participant + " never confirmed anything");
        }
        return said;
    }

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
