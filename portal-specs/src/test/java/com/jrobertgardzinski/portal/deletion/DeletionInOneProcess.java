package com.jrobertgardzinski.portal.deletion;

import com.jrobertgardzinski.collections.deletion.CollectionsDeletionParticipant;
import com.jrobertgardzinski.comments.application.CommentEvents;
import com.jrobertgardzinski.comments.deletion.CommentsDeletionParticipant;
import com.jrobertgardzinski.deletion.CommentsDeleted;
import com.jrobertgardzinski.deletion.MemeDeleted;
import com.jrobertgardzinski.memes.application.DeleteMeme;
import com.jrobertgardzinski.memes.application.MemeEvents;
import com.jrobertgardzinski.portal.races.Scheduler;
import com.jrobertgardzinski.portal.races.Wire;
import com.jrobertgardzinski.portal.world.FakeComments;
import com.jrobertgardzinski.portal.world.FakeFavourites;
import com.jrobertgardzinski.portal.world.FakeMemes;
import com.jrobertgardzinski.portal.world.Portal;
import com.jrobertgardzinski.portal.world.UnitsOfWork;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The deletion cascade in one process: the real {@link DeleteMeme}, the real two hops over
 * {@link Portal}'s rows, and a {@link Wire} in place of two Kafka topics.
 *
 * <p>There is no router here and no confirmation, because there is no orchestrator: each
 * announcement simply goes to everyone who subscribes to it, and nobody waits. That absence is
 * the difference between this class and {@code closure.ClosureInOneProcess}, and it is the
 * difference the specs next door are about.
 *
 * <p>Since the wire replaced a plain list, the two hops of one announcement are two STEPS on two
 * lanes rather than two calls inside one method. Nothing about the specs changed — with
 * {@link Scheduler#FIFO} the steps come out in the order the method called them — but the fact
 * that production runs those two hops in two independent consumer groups is now a thing the
 * transport knows rather than a thing a comment claims.
 */
public final class DeletionInOneProcess {

    /**
     * The cascade's consumer groups, and deliberately not the same names the saga's participants
     * use. They are the same three SERVICES, and two different consumer groups inside each: a
     * comments service whose saga consumer has stopped is still consuming the cascade's topic, and
     * a runner that held one by holding the other would be staging a failure production cannot
     * have.
     */
    static final String COMMENTS = "comments-cascade";
    static final String COLLECTIONS = "collections-cascade";

    /** Keyed by the meme, so one meme's whole cascade stays on one partition — {@code KafkaMemeEvents}. */
    public static final String MEMES_EVENTS = "memes-events";

    /** Keyed by the meme as well, and deliberately: {@code KafkaCommentEvents} says why. */
    public static final String COMMENTS_EVENTS = "comments-events";

    /** The two topics of the cascade — what {@link #everyHopAnswers()} drains and nothing else. */
    public static final Predicate<Wire.Lane> CASCADE =
            lane -> MEMES_EVENTS.equals(lane.topic()) || COMMENTS_EVENTS.equals(lane.topic());

    public final FakeMemes memes;
    public final FakeComments comments;
    public final FakeFavourites favourites;

    /** What has been announced and not yet delivered — the whole of the "broker". */
    private final Wire wire;

    /**
     * The world's transactions. Every announcement below goes out through them, so a cascade a
     * rolled-back step announced is a cascade that was never announced — the outbox, which is what
     * {@code KafkaMemeEvents} writes into rather than producing straight to the broker.
     */
    private final UnitsOfWork transactions;

    private final MemeEvents memeEvents;
    private final CommentEvents commentEvents;
    private final DeleteMeme deleteMeme;
    private final CommentsDeletionParticipant commentsParticipant;
    private final CollectionsDeletionParticipant collectionsParticipant;

    /** The last MEME_DELETED announced, so a scenario can have the broker deliver it twice. */
    private MemeDeleted lastMemeDeleted;

    /** Every COMMENTS_DELETED that was ever announced, for the specs to count. */
    private final List<CommentsDeleted> commentAnnouncements = new ArrayList<>();

    /** The cascade over a portal of its own — what the deletion specs next door drive. */
    public DeletionInOneProcess() {
        this(new Portal(), new Wire());
    }

    /**
     * The cascade over a portal somebody else already holds. The account-closure bus builds one
     * of these so that {@code PurgeUserContent}'s announcements land on a real hop instead of a
     * mock: the two protocols act on the same rows in production, and the seam between them is
     * only statable from a runner where they act on the same rows here.
     *
     * <p>It takes that caller's WIRE too, for the same reason it takes its rows. Two wires would
     * have made the two protocols unorderable with respect to each other by construction — the
     * one thing production guarantees nothing about, and therefore the one thing worth being able
     * to schedule.
     */
    public DeletionInOneProcess(Portal world, Wire wire) {
        this.wire = wire;
        this.transactions = world.unitsOfWork();
        this.memes = world.memes;
        this.comments = world.comments;
        this.favourites = world.favourites;
        memeEvents = memeId -> MemeDeleted.of(memeId).ifPresent(this::announce);
        commentEvents = (memeId, commentIds) ->
                CommentsDeleted.of(memeId, commentIds).ifPresent(this::announce);

        deleteMeme = world.deleteMeme(memeEvents);
        // the hop's unit of work is the world's: one service, one transaction manager
        commentsParticipant = world.commentsDeletion(commentEvents, transactions);
        collectionsParticipant = world.collectionsDeletion();
    }

    /** The cascade over a portal somebody else holds, on a wire of its own. */
    public DeletionInOneProcess(Portal world) {
        this(world, new Wire());
    }

    /**
     * MEME_DELETED reaches BOTH hops — comments drops the thread, collections drops the refs to
     * the meme. Two consumer groups, so two lanes: production has no way to order them against
     * each other and neither has this.
     */
    private void announce(MemeDeleted announcement) {
        transactions.onCommit(() -> {
            lastMemeDeleted = announcement;
            enqueue(announcement);
        });
    }

    private void enqueue(MemeDeleted announcement) {
        String memeId = announcement.memeId();
        wire.enqueue(new Wire.Lane(MEMES_EVENTS, memeId, COMMENTS),
                "MEME_DELETED " + memeId,
                () -> commentsParticipant.handle(announcement));
        wire.enqueue(new Wire.Lane(MEMES_EVENTS, memeId, COLLECTIONS),
                "MEME_DELETED " + memeId,
                () -> collectionsParticipant.handle(announcement));
    }

    /** COMMENTS_DELETED reaches collections alone. */
    private void announce(CommentsDeleted announcement) {
        transactions.onCommit(() -> {
            commentAnnouncements.add(announcement);
            wire.enqueue(new Wire.Lane(COMMENTS_EVENTS, announcement.memeId(), COLLECTIONS),
                    "COMMENTS_DELETED " + announcement.memeId()
                            + " " + announcement.commentIds().stream().sorted().toList(),
                    () -> collectionsParticipant.handle(announcement));
        });
    }

    /**
     * The port the cascade listens on. Whoever holds it can start a cascade — the author's own
     * teardown does it through {@link #takeDown}, and an account closure does it from inside its
     * irreversible half, which is the seam {@code closure.ClosureInOneProcess} wires up.
     */
    public MemeEvents memeEvents() {
        return memeEvents;
    }

    /**
     * The port the cascade's SECOND hop is announced on. An account closure destroys comments of
     * its own, under memes it is not touching, and those announcements join this same wire.
     */
    public CommentEvents commentEvents() {
        return commentEvents;
    }

    /** Is anything still on the wire — an announcement made and not yet delivered to a hop? */
    public boolean somethingIsInFlight() {
        return wire.anything(CASCADE);
    }

    /** The author (or a moderator) takes a meme down — where the cascade starts. */
    public DeleteMeme.Result takeDown(String memeId) {
        return deleteMeme.execute(memeId);
    }

    /**
     * Something announces a deletion of its own — a mis-produced event, say. It goes through the
     * SAME port memes announces through, so what the cascade will and will not carry is decided
     * where it is decided in production.
     */
    public void announceDeletionOf(String memeId) {
        memeEvents.memeDeleted(memeId);
    }

    /** That hop of the cascade stops consuming; its announcements wait for it on the wire. */
    public void silence(String hop) {
        wire.hold(hop);
    }

    /**
     * Drains the cascade's two topics: every hop hears what it subscribes to, and whatever it
     * announces joins the queue. The order is {@link Scheduler#FIFO}'s, which is the order the
     * {@code while (!inFlight.isEmpty())} loop used to produce.
     */
    public void everyHopAnswers() {
        everyHopAnswers(Scheduler.FIFO);
    }

    /** The same, in whatever order is asked for — what the races next door vary. */
    public void everyHopAnswers(Scheduler scheduler) {
        wire.drain(scheduler, CASCADE);
    }

    /** How many times the comments part announced anything about this meme. */
    public long commentAnnouncementsAbout(String memeId) {
        return commentAnnouncements.stream()
                .filter(announced -> announced.memeId().equals(memeId))
                .count();
    }

    /**
     * The broker delivers the last MEME_DELETED a second time — at-least-once, which the portal
     * gets for free and has to survive.
     */
    public void redeliver() {
        Optional.ofNullable(lastMemeDeleted).ifPresent(this::enqueue);
    }

    /** The transport itself — what a race runner schedules. */
    public Wire wire() {
        return wire;
    }
}
