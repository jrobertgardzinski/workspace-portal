package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.closure.ClosureConfirmations;
import com.jrobertgardzinski.collections.application.MarkUserItemsForErasure;
import com.jrobertgardzinski.collections.application.PurgeDeletedItem;
import com.jrobertgardzinski.collections.application.PurgeUserItems;
import com.jrobertgardzinski.collections.application.RestoreUserItems;
import com.jrobertgardzinski.collections.closure.CollectionsClosureParticipant;
import com.jrobertgardzinski.collections.deletion.CollectionsDeletionParticipant;
import com.jrobertgardzinski.comments.application.CommentEvents;
import com.jrobertgardzinski.comments.application.FakeCommentVotes;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.comments.closure.CommentsClosureParticipant;
import com.jrobertgardzinski.comments.deletion.CommentsDeletionParticipant;
import com.jrobertgardzinski.memes.application.DeleteMeme;
import com.jrobertgardzinski.memes.application.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.application.MemeContentIndex;
import com.jrobertgardzinski.memes.application.MemeEvents;
import com.jrobertgardzinski.memes.application.FakePurgePolicyOverride;
import com.jrobertgardzinski.memes.application.PurgeUserContent;
import com.jrobertgardzinski.memes.application.RestoreUserContent;
import com.jrobertgardzinski.memes.application.TagRepository;
import com.jrobertgardzinski.memes.application.FakeVoteRepository;
import com.jrobertgardzinski.memes.closure.MemesClosureParticipant;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.unitofwork.UnitOfWork;

import com.jrobertgardzinski.voting.VoteDirection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;

/**
 * The portal itself, with no bus attached: three services' rows in this process, their real use
 * cases, and the participants of both cross-service protocols built over them.
 *
 * <p>One world, two protocols, because there is one portal. A second specs module would have
 * meant a second copy of these three fakes — or a third module to share them — and the copies
 * would have drifted, which is the failure this repository has already paid for twice.
 *
 * <p>What is NOT here is how a message travels. Closing an account is an orchestrated saga with
 * a router and confirmations ({@code closure.ClosureInOneProcess}); deleting a meme is a
 * choreography with no orchestrator at all ({@code deletion.DeletionInOneProcess}). Each brings
 * its own bus and its own ports; this class brings the portal they both act on.
 */
public final class Portal {

    public final FakeMemes memes = new FakeMemes();
    public final FakeComments comments = new FakeComments();
    public final FakeFavourites favourites = new FakeFavourites();

    /**
     * The ballots, and the admin's dial. Real fakes rather than mocks, because a score is not
     * scenery: {@code KEEP_POPULAR_ANONYMIZED} is the one purge rule that reads one, and the
     * ordering both purges depend on — the leaver's own ballots retracted BEFORE any score is read
     * — is invisible to a store that answers 0 whatever happens to it. They come from the services'
     * own test-jars, so the specs and each service's use-case tests count votes the same way.
     */
    private final Map<String, Map<String, VoteDirection>> memeBallots = new HashMap<>();
    private final Map<String, Map<String, VoteDirection>> commentBallots = new HashMap<>();

    public final FakeVoteRepository memeVotes = new FakeVoteRepository(memeBallots);
    public final FakeCommentVotes commentVotes = new FakeCommentVotes(commentBallots);
    public final FakePurgePolicyOverride purgePolicy = new FakePurgePolicyOverride();

    /**
     * This world's transactions, and the reason a participant here can be made to fail. Every
     * participant this class builds shares it, because in the deployed stack they share the one
     * Spring transaction manager per service — and a runner where each part failed on its own
     * schedule would be staging something no deployment can do.
     */
    private final UnitsOfWork unitsOfWork = new UnitsOfWork(this);

    /**
     * Rows that belong to this world and are not held by this class — the orchestrator's saga
     * table, which lives in the bus because the bus is the only thing in the estate that has one.
     * A transaction that rolls back has to put those back too, or half the failure would stay.
     */
    private final List<Supplier<Snapshot>> alsoHeld = new ArrayList<>();

    /** The specs' clock; a saga's patience is measured against it. */
    private Instant now = Instant.parse("2026-09-24T12:00:00Z");

    public Clock clock() {
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
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
    }

    public void windForward(Duration by) {
        now = now.plus(by);
    }

    // ---- the whole world, read at once -------------------------------------------------------

    /**
     * Every row in this process, canonically ordered — the one total read of the world.
     *
     * <p>Every assertion in {@code ../specs} is a POINT read: "nothing is left under their first
     * meme", "the stranger still has that comment saved". A point read answers a question somebody
     * already thought to ask. Comparing two runs — which is what asking "does the order matter"
     * means — needs a read that answers all of them at once, including the ones nobody asked.
     *
     * <p>Three uses, and each is a reason this is one method and not three: it groups
     * interleavings into their distinct outcomes, it prunes the search (two interleavings that
     * reached the same fingerprint with the same wire in front of them have the same future), and
     * it IS the idempotence law — a duplicate that changed nothing changed no character here.
     *
     * <p>What it deliberately leaves out: the instant of a reservation (see
     * {@link FakeMemes#rows()}) and the identity of a saga row. What the saga DID is read where
     * the portal can see it — the verdicts the bus sent to security — and not out of the
     * orchestrator's private map.
     */
    public String fingerprint() {
        List<String> lines = new ArrayList<>();
        lines.addAll(memes.rows());
        lines.addAll(comments.rows());
        lines.addAll(favourites.rows());
        lines.addAll(ballots("meme-vote", memeBallots));
        lines.addAll(ballots("comment-vote", commentBallots));
        purgePolicy.current().ifPresent(rule -> lines.add("purge-dial " + rule));
        return String.join("\n", lines);
    }

    private List<String> ballots(String what, Map<String, Map<String, VoteDirection>> votes) {
        List<String> lines = new ArrayList<>();
        votes.forEach((subject, cast) -> cast.forEach((voter, direction) ->
                lines.add(what + " " + subject + " " + voter + " " + direction)));
        return lines.stream().sorted().toList();
    }

    /** Every meme id held right now — what a dangling pointer is checked against. */
    public List<String> memeIds() {
        return memes.everyId();
    }

    /** Every comment id held right now. */
    public List<String> commentIds() {
        return comments.everyId();
    }

    /**
     * The whole world as it is now, and the one way back to it — what {@link UnitsOfWork} takes
     * before a step and puts back when that step's transaction does not commit.
     *
     * <p>It is the same reach as {@link #fingerprint()} and deliberately so: anything a rollback
     * forgot to put back would be a difference the fingerprint reports, and the laws next door read
     * the portal through nothing else. The ballots are here for that reason and not because a
     * closure votes — {@code PurgeUserContent} retracts the leaver's ballots before it reads a
     * score, and a rollback that left them retracted would change what the next attempt decides.
     */
    public Snapshot snapshot() {
        List<Snapshot> parts = new ArrayList<>(
                List.of(memes.snapshot(), comments.snapshot(), favourites.snapshot()));
        alsoHeld.forEach(part -> parts.add(part.get()));
        Snapshot rows = Snapshot.of(parts.toArray(Snapshot[]::new));
        Map<String, Map<String, VoteDirection>> memeBallotsThen = ballotsNow(memeBallots);
        Map<String, Map<String, VoteDirection>> commentBallotsThen = ballotsNow(commentBallots);
        Optional<PurgeRule> dialThen = purgePolicy.current();
        return () -> {
            rows.restore();
            putBallotsBack(memeBallots, memeBallotsThen);
            putBallotsBack(commentBallots, commentBallotsThen);
            dialThen.ifPresentOrElse(rule -> purgePolicy.set(rule, "a unit of work that rolled back"),
                    () -> purgePolicy.clear("a unit of work that rolled back"));
        };
    }

    /** Rows of this world that somebody else holds, and how to read them back. */
    public void alsoRestoring(Supplier<Snapshot> part) {
        alsoHeld.add(part);
    }

    /** The transactions every participant over this world shares. */
    public UnitsOfWork unitsOfWork() {
        return unitsOfWork;
    }

    private static Map<String, Map<String, VoteDirection>> ballotsNow(
            Map<String, Map<String, VoteDirection>> votes) {
        Map<String, Map<String, VoteDirection>> copy = new HashMap<>();
        votes.forEach((subject, cast) -> copy.put(subject, new HashMap<>(cast)));
        return copy;
    }

    private static void putBallotsBack(Map<String, Map<String, VoteDirection>> votes,
                                       Map<String, Map<String, VoteDirection>> then) {
        votes.clear();
        votes.putAll(ballotsNow(then));
    }

    // ---- account closure: this service's participant, as deployed ----------------------------

    /**
     * The memes part of the saga, announcing through the port given — the SAME port the author's
     * own teardown announces through, because {@code PurgeUserContent} reuses the cascade rather
     * than deleting a thread a second time. A caller that hands it the deletion bus is wiring the
     * two protocols together exactly as {@code SagaParticipantConfig} does in the deployed stack.
     */
    public MemesClosureParticipant memesClosure(ClosureConfirmations confirmations,
                                                MemeEvents memeEvents) {
        return new MemesClosureParticipant(
                new MarkUserContentForErasure(memes, clock()),
                new RestoreUserContent(memes),
                new PurgeUserContent(memes, memes, memeVotes,
                        mock(MemeContentIndex.class), mock(TagRepository.class),
                        memeEvents, purgePolicy,
                        new PurgeRule.Delete()),
                confirmations, Observations.silent(), unitsOfWork);
    }

    /**
     * The comments part of the saga, announcing what it destroyed through the port given — the same
     * port the cascade's own hop announces through, for the same reason the memes part gets one: a
     * comment this closure deletes may be saved in a collection belonging to somebody who is not
     * leaving.
     */
    public CommentsClosureParticipant commentsClosure(ClosureConfirmations confirmations,
                                                      CommentEvents commentEvents) {
        return new CommentsClosureParticipant(
                new MarkUserCommentsForErasure(comments, clock()),
                new RestoreUserComments(comments),
                new PurgeUserComments(comments, comments, commentVotes,
                        new PurgeRule.Delete()),
                commentEvents, confirmations, Observations.silent(), unitsOfWork);
    }

    public CollectionsClosureParticipant collectionsClosure() {
        return new CollectionsClosureParticipant(
                new MarkUserItemsForErasure(favourites, clock()),
                new RestoreUserItems(favourites),
                new PurgeUserItems(favourites),
                Observations.silent());
    }

    // ---- meme deletion: the use case that starts it, and the two hops ------------------------

    /** Where the cascade starts: the author's own teardown, announcing through the port given. */
    public DeleteMeme deleteMeme(MemeEvents memeEvents) {
        return new DeleteMeme(memes, memeVotes, mock(MemeContentIndex.class),
                mock(TagRepository.class), memeEvents);
    }

    public CommentsDeletionParticipant commentsDeletion(CommentEvents commentEvents,
                                                        UnitOfWork unitOfWork) {
        return new CommentsDeletionParticipant(
                new DeleteThread(comments, comments, commentVotes),
                commentEvents, unitOfWork);
    }

    public CollectionsDeletionParticipant collectionsDeletion() {
        return new CollectionsDeletionParticipant(new PurgeDeletedItem(favourites));
    }
}
