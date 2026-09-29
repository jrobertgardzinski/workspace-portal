package com.jrobertgardzinski.portal.closure;

import com.jrobertgardzinski.identity.UserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureConfirmation;
import com.jrobertgardzinski.closure.ClosureConfirmations;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.closure.ClosureOutcome;
import com.jrobertgardzinski.closure.ClosureParticipant;
import com.jrobertgardzinski.collections.closure.CollectionsClosureParticipant;
import com.jrobertgardzinski.comments.closure.CommentsClosureParticipant;
import com.jrobertgardzinski.memes.closure.MemesClosureParticipant;
import com.jrobertgardzinski.portal.races.Scheduler;
import com.jrobertgardzinski.portal.races.Wire;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The account-closure saga in one process: the real {@link EventsRouter}, the real three
 * participants over {@link Portal}'s rows, and a {@link Wire} in place of the broker. Nothing
 * is reordered, duplicated or dropped at random; {@link #silence} is the only failure staged.
 *
 * <p>This class is the saga's BUS. The portal it drives is {@link Portal}, and the deletion
 * cascade acts on the SAME rows, so this class holds that bus too ({@link #cascade}) rather than
 * letting a second {@code new Portal()} pretend the two protocols meet nowhere. There is no
 * orchestrator over there to share — only the rows, and the wire they both travel on.
 *
 * <p>One wire, two protocols, and the lanes keep them apart exactly as far as production does:
 * a command and its confirmations are ordered against each other, a cascade is ordered within one
 * meme, and NOTHING orders the two against each other. {@link #everyPartAnswers()} drains the
 * saga's lanes only — the cascade it set off is still in the air, and a scenario that wants it
 * delivered says so ({@link #cascadeReachesEveryPart}).
 */
public final class ClosureInOneProcess {

    static final String MEMES = "memes";
    static final String COMMENTS = "comments";
    static final String COLLECTIONS = "collections";

    /** Every command the saga sends rides this one, keyed by the leaver — {@code SagaTopics}. */
    public static final String CONTENT_COMMANDS = "content-commands";

    /** The single verdict identity waits for. */
    public static final String OFFBOARDING_EVENTS = "offboarding-events";

    /** One per participant, keyed by the saga it echoes — {@code PurgeConfirmations}. */
    public static String confirmationsOf(String participant) {
        return participant + "-purge-events";
    }

    /** The orchestrator is a consumer like any other, and its own group. */
    private static final String ORCHESTRATOR = "offboarding";

    private static final String SECURITY = "security";

    /** Everything that is not the cascade — what {@link #everyPartAnswers()} drains. */
    public static final Predicate<Wire.Lane> SAGA = lane -> !DeletionInOneProcess.CASCADE.test(lane);

    private static final Duration PURGE_TIMEOUT = Duration.ofMinutes(2);

    private final ObjectMapper mapper = new ObjectMapper();

    private final Portal world = new Portal();

    /** The transport both protocols travel on: one broker, many lanes. */
    private final Wire wire = new Wire();

    /**
     * The OTHER protocol, over the same rows and the same wire. {@code PurgeUserContent} announces
     * every meme it destroys, and in the deployed stack that announcement is a {@code MEME_DELETED}
     * on the cascade's topic — so one account closure starts N cascades, after the pivot, with
     * nobody orchestrating them and nothing to compensate with. A mock here made that seam
     * unstatable: the rule {@code anonymise} and the rule {@code delete} looked identical to
     * everything outside the memes part.
     *
     * <p>It is NOT drained by {@link #everyPartAnswers}. The saga is finished the moment the last
     * confirmation lands; the cascade it set off is still in the air, and a scenario that wants
     * it delivered says so ({@link #cascadeReachesEveryPart}).
     */
    private final DeletionInOneProcess cascade = new DeletionInOneProcess(world, wire);

    // the steps read the portal through this class; the rows themselves are the world's
    final com.jrobertgardzinski.memes.application.FakeVoteRepository memeVotes = world.memeVotes;
    final com.jrobertgardzinski.comments.application.FakeCommentVotes commentVotes = world.commentVotes;
    public final FakeMemes memes = world.memes;
    public final FakeComments comments = world.comments;
    public final FakeFavourites favourites = world.favourites;

    private final FakeSagaStore sagas = new FakeSagaStore();
    private final EventsRouter router;
    private final MemesClosureParticipant memesParticipant;
    private final CommentsClosureParticipant commentsParticipant;
    private final CollectionsClosureParticipant collectionsParticipant;

    private final List<JsonNode> toSecurity = new ArrayList<>();

    /** The reservation each part last confirmed — the only count that ever leaves the portal. */
    private final Map<String, Integer> confirmedBy = new LinkedHashMap<>();

    /**
     * The same counts, addressed by WHOSE closure they were. Two people can be leaving at once —
     * their sagas ride different partitions and nothing orders them — and a map keyed by the part
     * alone would answer with whichever of the two happened to speak first, which is not a fact
     * about the portal but a fact about the schedule.
     */
    private final Map<String, Integer> confirmedFor = new LinkedHashMap<>();

    /** Told whenever a part answers, so a race runner can hold it to what it said. */
    private Confirming watcher = (participant, leaver, reserved) -> { };

    /** What a part said it had reserved, and to whom, at the instant it said it. */
    @FunctionalInterface
    public interface Confirming {
        void answered(String participant, UserId leaver, int reserved);
    }

    public ClosureInOneProcess() {
        Set<String> participants = Set.of(MEMES, COMMENTS, COLLECTIONS);
        router = new EventsRouter(
                new BeginOffboarding(sagas, participants),
                new RecordConfirmation(sagas, participants),
                new SweepOverdue(sagas, PURGE_TIMEOUT),
                mapper, world.clock());

        // no outbox in here: the confirmation is what the wire carries back
        ClosureConfirmations nothingToAnnounce = (sagaId, leaver, reserved) -> { };
        memesParticipant = world.memesClosure(nothingToAnnounce, cascade.memeEvents());
        commentsParticipant = world.commentsClosure(nothingToAnnounce, cascade.commentEvents());
        collectionsParticipant = world.collectionsClosure();
    }

    /** Every hop of every cascade this closure set off hears it — which nobody waits for. */
    public void cascadeReachesEveryPart() {
        cascade.everyHopAnswers();
    }

    /** Somebody takes a meme down while the saga is mid-flight. */
    public void takeDown(String memeId) {
        cascade.takeDown(memeId);
    }

    /** Was anything announced at all — the difference between a meme deleted and one anonymised. */
    public boolean cascadeIsInFlight() {
        return cascade.somethingIsInFlight();
    }

    public void silence(String participant) {
        wire.hold(participant);
    }

    public void securityAnnouncesClosureOf(String email, String initiatedBy, String policyJson) {
        String fact = "{\"id\":\"" + UUID.nameUUIDFromBytes(("fact:" + email).getBytes())
                + "\",\"type\":\"" + ClosureMessages.ACCOUNT_DELETION_REQUESTED + "\","
                + "\"email\":\"" + email + "\","
                + "\"" + ClosureMessages.Field.USER_ID + "\":\"" + Identities.idOf(email) + "\","
                + "\"" + ClosureMessages.Field.INITIATED_BY + "\":\"" + initiatedBy + "\""
                + (policyJson == null ? "" : ",\"" + ClosureMessages.Field.POLICY + "\":" + policyJson)
                + ",\"version\":1}";
        enqueue(router.handle(Source.SECURITY, fact));
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
        sweep();
        everyPartAnswers();
    }

    /**
     * The clock reaches the timeout and the sweeper runs — and nothing else. Split out of
     * {@link #waitsAndAsksAgain} because a sweep is one STEP of the transport, and a runner that
     * always followed it with a full drain could never ask what happens when a late confirmation
     * and the verdict are in the air at the same time.
     */
    public void sweep() {
        world.windForward(PURGE_TIMEOUT.plusSeconds(1));
        Instant now = world.clock().instant();
        List<EventsRouter.Outgoing> swept = router.sweepOverdue();
        swept.stream().map(EventsRouter.Outgoing::countsRetryFor).filter(Objects::nonNull)
                .forEach(charge -> sagas.retryDelivered(charge.sagaId(), charge.retriesSoFar(), now));
        enqueue(swept);
    }

    /** The part comes back — a restarted consumer, which is how a silence ends in production. */
    public void hearsAgain(String participant) {
        wire.release(participant);
    }

    /**
     * Drains the saga's lanes: commands reach the parts, confirmations reach the orchestrator, and
     * whatever it answers joins the wire. {@link Scheduler#FIFO} is the order the old
     * {@code while (!inFlight.isEmpty())} loop produced.
     */
    public void everyPartAnswers() {
        everyPartAnswers(Scheduler.FIFO);
    }

    /** The same, in whatever order is asked for — what the races next door vary. */
    public void everyPartAnswers(Scheduler scheduler) {
        wire.drain(scheduler, SAGA);
    }

    /**
     * One outgoing record becomes one step per consumer group. A command has three — production
     * runs the three participants in three groups and orders them against each other in no way at
     * all — and a verdict has one, security's.
     */
    private void enqueue(List<EventsRouter.Outgoing> messages) {
        for (EventsRouter.Outgoing message : messages) {
            // the outbox's second half, which this bus did not have until the races next door
            // swept a finished saga and watched it announce its verdict a fifth time. The deployed
            // loop marks a saga announced once its events are PROVEN at the broker
            // (KafkaLoop#settleDeliveries) and the sweeper re-publishes whatever never got the
            // mark; nothing here can fail to send, so publishing IS the proof. Without this the
            // sweeper is a machine for re-announcing verdicts, and no spec noticed because no
            // spec had ever swept a saga that was already finished.
            if (message.announcesSaga() != null) {
                sagas.markAnnounced(message.announcesSaga());
            }
            if (message.destination() == Destination.SECURITY) {
                JsonNode verdict = read(message.payload());
                wire.enqueue(new Wire.Lane(OFFBOARDING_EVENTS, message.key(), SECURITY),
                        "verdict " + verdict.path(ClosureMessages.Field.TYPE).asText(),
                        () -> toSecurity.add(verdict));
                continue;
            }
            JsonNode command = read(message.payload());
            String type = command.path(ClosureMessages.Field.TYPE).asText();
            for (String participant : List.of(MEMES, COMMENTS, COLLECTIONS)) {
                wire.enqueue(new Wire.Lane(CONTENT_COMMANDS, message.key(), participant),
                        type + " → " + participant,
                        () -> commandReaches(participant, command));
            }
        }
    }

    /** The part hears its command, answers or does not, and its answer is a record of its own. */
    private void commandReaches(String participant, JsonNode command) {
        answerOf(participant, command).ifPresent(confirmation -> {
            String sagaId = command.path(ClosureMessages.Field.SAGA_ID).asText();
            wire.enqueue(new Wire.Lane(confirmationsOf(participant), sagaId, ORCHESTRATOR),
                    "confirmation from " + participant,
                    () -> enqueue(router.handle(Source.participant(participant), confirmation)));
        });
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
        confirmedFor.putIfAbsent(leaver + "/" + participant, reserved);
        watcher.answered(participant, leaver, reserved);
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

    /**
     * How many rows this part is holding reserved for this person RIGHT NOW — what a confirmation
     * can be held against. A part may truthfully confirm fewer (a re-commanded MARK finds
     * everything already marked and answers 0); it may never truthfully confirm more.
     */
    public int reservedOn(String participant, UserId leaver) {
        return switch (participant) {
            case MEMES -> memes.pendingOf(leaver).size();
            case COMMENTS -> comments.pendingOf(leaver).size();
            case COLLECTIONS -> favourites.pendingOf(leaver).size();
            default -> throw new IllegalStateException("no such participant: " + participant);
        };
    }

    /** What each part said it had reserved, the first time it answered — part of an outcome. */
    public Map<String, Integer> confirmations() {
        return Map.copyOf(confirmedFor);
    }

    /** Every verdict security was given, by type — what a fingerprint reads of the saga. */
    public List<String> verdicts() {
        return toSecurity.stream()
                .map(said -> said.path(ClosureMessages.Field.TYPE).asText())
                .toList();
    }

    /** The world both protocols act on. */
    public Portal world() {
        return world;
    }

    /** The transport itself — what a race runner schedules. */
    public Wire wire() {
        return wire;
    }

    /** The cascade this closure sets off, for a runner that wants to drain it a step at a time. */
    public DeletionInOneProcess cascade() {
        return cascade;
    }

    public void watch(Confirming watcher) {
        this.watcher = watcher;
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
