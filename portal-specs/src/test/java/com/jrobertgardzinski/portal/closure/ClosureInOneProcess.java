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
import com.jrobertgardzinski.portal.world.Snapshot;
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

    /**
     * The lanes whose consumer does its work inside a transaction that could fail — the only
     * records a runner may sensibly stage a rollback for.
     *
     * <p>Three of the six consumers, and the three the estate makes atomic: the two participants
     * that owe the orchestrator a confirmation ({@code AtomicClosureParticipant}), and the
     * cascade's comments hop, which deletes a thread and announces what it deleted in one unit of
     * work. Collections is deliberately absent on both protocols — it has no outbox to write into
     * and so no transaction to share — and so is the orchestrator: its own store is not part of the
     * world a snapshot puts back, which makes ITS transaction a boundary of this layer rather than
     * something staged badly.
     */
    /**
     * Of those, the ones whose records are written to an OUTBOX TABLE inside the transaction — the
     * three participant consumers, which publish through `SpringOutbox` and a republisher.
     *
     * <p>The orchestrator is transactional and not here: its loop and its sweeper send straight to
     * the broker and mark the saga once the send is proven, so a death before the send loses the
     * records and the next sweep re-issues them. Modelling those as relayable outbox rows staged a
     * failure the deployed loop cannot have — and produced a law break to match.
     */
    public static final Predicate<Wire.Lane> THROUGH_AN_OUTBOX = lane ->
            (CONTENT_COMMANDS.equals(lane.topic())
                    && (MEMES.equals(lane.group()) || COMMENTS.equals(lane.group())))
                    || (DeletionInOneProcess.MEMES_EVENTS.equals(lane.topic())
                    && DeletionInOneProcess.COMMENTS.equals(lane.group()));

    public static final Predicate<Wire.Lane> TRANSACTIONAL = lane ->
            (CONTENT_COMMANDS.equals(lane.topic())
                    && (MEMES.equals(lane.group()) || COMMENTS.equals(lane.group())))
                    || (DeletionInOneProcess.MEMES_EVENTS.equals(lane.topic())
                    && DeletionInOneProcess.COMMENTS.equals(lane.group()))
                    || ORCHESTRATOR.equals(lane.group());

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

    /** Whose closure, and whose part, each of those keys is — so the key can be asked again later. */
    private final Map<String, UserId> spokenOf = new LinkedHashMap<>();

    /** Told whenever a part answers, so a race runner can hold it to what it said. */
    private Confirming watcher = (participant, leaver, reserved) -> { };

    /** What a part said it had reserved, and to whom, at the instant it said it. */
    @FunctionalInterface
    public interface Confirming {
        void answered(String participant, UserId leaver, int reserved);
    }

    public ClosureInOneProcess() {
        // the saga table is this world's row too, and a rollback has to put it back
        world.alsoRestoring(this::sagaRowsNow);
        Set<String> participants = Set.of(MEMES, COMMENTS, COLLECTIONS);
        router = new EventsRouter(
                new BeginOffboarding(sagas, participants),
                new RecordConfirmation(sagas, participants),
                new SweepOverdue(sagas, PURGE_TIMEOUT),
                mapper, world.clock());

        memesParticipant = world.memesClosure(outboxOf(MEMES), cascade.memeEvents());
        commentsParticipant = world.commentsClosure(outboxOf(COMMENTS), cascade.commentEvents());
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
        // the clock moves whatever happens next: a transaction that does not commit does not give
        // the time back, and the next tick finds the same cases overdue
        world.windForward(PURGE_TIMEOUT.plusSeconds(1));
        Instant now = world.clock().instant();
        // the sweeper's own unit of work: what it selects, the retries it charges and the records it
        // produces are one transaction, exactly as the participants' work and word are
        world.unitsOfWork().run(() -> {
            List<EventsRouter.Outgoing> swept = router.sweepOverdue();
            swept.stream().map(EventsRouter.Outgoing::countsRetryFor).filter(Objects::nonNull)
                    .forEach(charge -> sagas.retryDelivered(charge.sagaId(), charge.retriesSoFar(), now));
            enqueue(swept);
        });
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
            if (message.destination() == Destination.SECURITY) {
                JsonNode verdict = read(message.payload());
                String label = "verdict " + verdict.path(ClosureMessages.Field.TYPE).asText();
                world.unitsOfWork().onCommit(label, () -> {
                    // marked when the record is SENT and not when it is produced, because
                    // KafkaLoop marks a saga announced once the broker has proven the send and
                    // the sweeper re-publishes whatever never got the mark. Marking at produce
                    // time would make a verdict lost between the commit and the send look
                    // announced, and nothing would ever say it
                    if (message.announcesSaga() != null) {
                        sagas.markAnnounced(message.announcesSaga());
                    }
                    wire.enqueue(new Wire.Lane(OFFBOARDING_EVENTS, message.key(), SECURITY), label,
                            () -> toSecurity.add(verdict));
                });
                continue;
            }
            JsonNode command = read(message.payload());
            String type = command.path(ClosureMessages.Field.TYPE).asText();
            for (String participant : List.of(MEMES, COMMENTS, COLLECTIONS)) {
                String label = type + " → " + participant;
                world.unitsOfWork().onCommit(label, () -> wire.enqueue(
                        new Wire.Lane(CONTENT_COMMANDS, message.key(), participant), label,
                        () -> commandReaches(participant, command)));
            }
        }
    }

    /**
     * The port an atomic participant confirms through — its outbox.
     *
     * <p>The record is written INSIDE the unit of work that hid the rows, and leaves with it or not
     * at all. Until 29.09.2026 this was {@code (sagaId, leaver, reserved) -> { }} and the bus built
     * the confirmation itself out of the count the participant returned — which cannot come apart
     * from the mark no matter what fails, and so made the one failure
     * {@code AtomicClosureParticipant} exists to prevent unstatable here.
     */
    private ClosureConfirmations outboxOf(String participant) {
        return (sagaId, leaver, reserved) -> {
            // asked HERE, where the word is written, and not where the record is sent. With an
            // outbox those are two different moments on purpose: the row is written inside the
            // transaction that hid the rows, and the relay sends it whenever it comes round — by
            // which time the erasure may have taken every row the word was about. Asking the law
            // at the relay called that a part confirming more than it held, which is the outbox
            // working rather than a part lying
            watcher.answered(participant, leaver, reserved);
            world.unitsOfWork().onCommit("confirmation from " + participant,
                    () -> confirms(participant, sagaId, leaver, reserved));
        };
    }

    /**
     * The part hears its command. The two atomic parts confirm from inside their own unit of work
     * through {@link #outboxOf}; collections confirms through its CONSUMER, which is this bus,
     * because it has no outbox to write into and so no transaction to share.
     */
    private void commandReaches(String participant, JsonNode command) {
        ClosureCommand parsed = parsed(participant, command);
        ClosureOutcome outcome = axisOf(participant).handle(parsed);
        if (COLLECTIONS.equals(participant)
                && outcome instanceof ClosureOutcome.Reserved(int reserved)) {
            // no outbox here, so writing the word and sending it are the same instant
            watcher.answered(participant, parsed.userId(), reserved);
            confirms(participant, parsed.sagaId(), parsed.userId(), reserved);
        }
    }

    /**
     * What a part said it reserved, on its way to the orchestrator. Called from inside the unit of
     * work's commit for the two atomic parts and straight from the delivery for collections — which
     * is the difference between the two kinds of participant, and the only place it shows.
     */
    private void confirms(String participant, String sagaId, UserId leaver, int reserved) {
        // the FIRST one: a re-commanded MARK finds everything already reserved and confirms 0,
        // which is idempotence working, not the count the saga was advanced on
        confirmedBy.putIfAbsent(participant, reserved);
        confirmedFor.putIfAbsent(leaver + "/" + participant, reserved);
        spokenOf.putIfAbsent(leaver + "/" + participant, leaver);
        String confirmation = written(new ClosureConfirmation(sagaId, leaver, reserved).fields());
        wire.enqueue(new Wire.Lane(confirmationsOf(participant), sagaId, ORCHESTRATOR),
                "confirmation from " + participant,
                // the orchestrator's own unit of work: the saga row it advances and the commands
                // and verdicts it produces are one transaction, as SagaOutbox makes them
                () -> world.unitsOfWork().run(
                        () -> enqueue(router.handle(Source.participant(participant), confirmation))));
    }

    /** Built from the same record the deployed participants read, so this transport cannot carry a different message. */
    private ClosureCommand parsed(String participant, JsonNode command) {
        String type = command.path(ClosureMessages.Field.TYPE).asText();
        String sagaId = command.path(ClosureMessages.Field.SAGA_ID).asText();
        String initiatedBy = command.path(ClosureMessages.Field.INITIATED_BY).asText();
        JsonNode rule = command.path(ClosureMessages.Field.POLICY).path(participant);
        UserId leaver = ClosureCommand.userIdOf(command.path(ClosureMessages.Field.USER_ID).asText(null)).orElse(null);
        return new ClosureCommand(type, sagaId, leaver, initiatedBy,
                rule.isMissingNode() ? Optional.empty() : Optional.of(rule.asText()));
    }

    // one vocabulary for all three since 28.09.2026: this used to be three fully-qualified
    // ClosureOutcome.Reserved patterns, one per service, in a switch that existed only because
    // the three said the same thing in three types
    private ClosureParticipant axisOf(String participant) {
        return switch (participant) {
            case MEMES -> memesParticipant;
            case COMMENTS -> commentsParticipant;
            case COLLECTIONS -> collectionsParticipant;
            default -> throw new IllegalStateException("no such participant: " + participant);
        };
    }

    private String written(Map<String, Object> fields) {
        try {
            return mapper.writeValueAsString(fields);
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

    /**
     * What the part behind each key of {@link #confirmations()} is holding reserved RIGHT NOW —
     * the other side of the same word, for the law that asks whether the two agree.
     */
    public Map<String, Integer> reservationsBehindConfirmations() {
        Map<String, Integer> held = new LinkedHashMap<>();
        confirmedFor.keySet().forEach(key -> {
            UserId leaver = spokenOf.get(key);
            String participant = key.substring(key.lastIndexOf('/') + 1);
            held.put(key, leaver == null ? 0 : reservedOn(participant, leaver));
        });
        return held;
    }

    /** Every verdict security was given, by type — what a fingerprint reads of the saga. */
    public List<String> verdicts() {
        return toSecurity.stream()
                .map(said -> said.path(ClosureMessages.Field.TYPE).asText())
                .toList();
    }

    /**
     * The saga rows as they are now, and the way back to them. Only the fields a step can move —
     * the state, the instant, whose confirmations have been counted, whether the outcome has been
     * announced and how many retries were charged.
     *
     * <p>A saga that a rolled-back transaction STARTED cannot be taken away again: the reference
     * fake has no way to forget a row, and adding one is a change in another repository. It costs
     * nothing here, because a saga is only ever opened by a fact delivered outside the wire —
     * security announcing a closure — and never inside a step this layer can fail.
     */
    private Snapshot sagaRowsNow() {
        record Row(FakeSagaStore.Saga saga, String state, java.time.Instant updatedAt,
                   Set<String> confirmed, boolean announced, int retries) {
        }
        List<Row> then = sagas.all().stream()
                .map(saga -> new Row(saga, saga.state, saga.updatedAt, Set.copyOf(saga.confirmed),
                        saga.announced, saga.retries))
                .toList();
        return () -> then.forEach(row -> {
            row.saga().state = row.state();
            row.saga().updatedAt = row.updatedAt();
            row.saga().confirmed.clear();
            row.saga().confirmed.addAll(row.confirmed());
            row.saga().announced = row.announced();
            row.saga().retries = row.retries();
        });
    }

    /**
     * Cases the orchestrator has finished and not recorded as announced — its OWN query, the one
     * {@code sweepOverdue} uses to decide what to publish again.
     */
    public List<String> waitingToBeAnnounced() {
        return sagas.unannouncedOutcomes(world.clock().instant().plusSeconds(1)).stream()
                .map(pending -> pending.state() + " " + pending.sagaId())
                .sorted()
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
