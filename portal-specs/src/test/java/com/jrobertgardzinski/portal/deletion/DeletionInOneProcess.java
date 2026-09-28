package com.jrobertgardzinski.portal.deletion;

import com.jrobertgardzinski.collections.deletion.CollectionsDeletionParticipant;
import com.jrobertgardzinski.comments.application.CommentEvents;
import com.jrobertgardzinski.comments.deletion.CommentsDeletionParticipant;
import com.jrobertgardzinski.deletion.CommentsDeleted;
import com.jrobertgardzinski.deletion.MemeDeleted;
import com.jrobertgardzinski.memes.application.DeleteMeme;
import com.jrobertgardzinski.memes.application.MemeEvents;
import com.jrobertgardzinski.portal.world.FakeComments;
import com.jrobertgardzinski.portal.world.FakeFavourites;
import com.jrobertgardzinski.portal.world.FakeMemes;
import com.jrobertgardzinski.portal.world.Portal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The deletion cascade in one process: the real {@link DeleteMeme}, the real two hops over
 * {@link Portal}'s rows, and an in-memory bus in place of two Kafka topics.
 *
 * <p>There is no router here and no confirmation, because there is no orchestrator: each
 * announcement simply goes to everyone who subscribes to it, and nobody waits. That absence is
 * the difference between this class and {@code closure.ClosureInOneProcess}, and it is the
 * difference the specs next door are about.
 */
public final class DeletionInOneProcess {

    static final String COMMENTS = "comments";
    static final String COLLECTIONS = "collections";

    public final FakeMemes memes;
    public final FakeComments comments;
    public final FakeFavourites favourites;

    /** What has been announced and not yet delivered — the whole of the "broker". */
    private final List<Object> inFlight = new ArrayList<>();

    private final Set<String> silenced = new HashSet<>();

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
        this(new Portal());
    }

    /**
     * The cascade over a portal somebody else already holds. The account-closure bus builds one
     * of these so that {@code PurgeUserContent}'s announcements land on a real hop instead of a
     * mock: the two protocols act on the same rows in production, and the seam between them is
     * only statable from a runner where they act on the same rows here.
     */
    public DeletionInOneProcess(Portal world) {
        this.memes = world.memes;
        this.comments = world.comments;
        this.favourites = world.favourites;
        memeEvents = memeId -> MemeDeleted.of(memeId).ifPresent(inFlight::add);
        commentEvents = (memeId, commentIds) ->
                CommentsDeleted.of(memeId, commentIds).ifPresent(announcement -> {
                    commentAnnouncements.add(announcement);
                    inFlight.add(announcement);
                });

        deleteMeme = world.deleteMeme(memeEvents);
        // the hop's unit of work: in one process there is one, and running the step IS it
        commentsParticipant = world.commentsDeletion(commentEvents, Runnable::run);
        collectionsParticipant = world.collectionsDeletion();
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
        return !inFlight.isEmpty();
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

    /** That part of the portal never hears anything from now on. */
    public void silence(String participant) {
        silenced.add(participant);
    }

    /**
     * Drains the bus: every hop hears what it subscribes to, and whatever it announces joins the
     * queue. MEME_DELETED reaches BOTH hops — comments drops the thread, collections drops the
     * refs to the meme — and COMMENTS_DELETED reaches collections alone.
     */
    public void everyHopAnswers() {
        while (!inFlight.isEmpty()) {
            List<Object> batch = List.copyOf(inFlight);
            inFlight.clear();
            for (Object announcement : batch) {
                deliver(announcement);
            }
        }
    }

    private void deliver(Object announcement) {
        if (announcement instanceof MemeDeleted memeDeleted) {
            lastMemeDeleted = memeDeleted;
            if (!silenced.contains(COMMENTS)) {
                commentsParticipant.handle(memeDeleted);
            }
            if (!silenced.contains(COLLECTIONS)) {
                collectionsParticipant.handle(memeDeleted);
            }
            return;
        }
        CommentsDeleted commentsDeleted = (CommentsDeleted) announcement;
        if (!silenced.contains(COLLECTIONS)) {
            collectionsParticipant.handle(commentsDeleted);
        }
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
        Optional.ofNullable(lastMemeDeleted).ifPresent(inFlight::add);
    }
}
