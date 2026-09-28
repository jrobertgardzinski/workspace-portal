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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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
    public final FakeVoteRepository memeVotes = new FakeVoteRepository();
    public final FakeCommentVotes commentVotes = new FakeCommentVotes();
    public final FakePurgePolicyOverride purgePolicy = new FakePurgePolicyOverride();

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
                confirmations, Observations.silent(), Runnable::run);
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
                commentEvents, confirmations, Observations.silent(), Runnable::run);
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
