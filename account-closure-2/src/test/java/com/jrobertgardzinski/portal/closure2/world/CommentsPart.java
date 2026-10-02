package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.comments.domain.FakeCommentRepository;
import com.jrobertgardzinski.comments.domain.FakeCommentVotes;
import com.jrobertgardzinski.comments.system.DeleteThread;
import com.jrobertgardzinski.comments.system.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.system.PurgeUserComments;
import com.jrobertgardzinski.comments.system.RestoreUserComments;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.voting.VoteDirection;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The threads: comments-domain's own fake of its ports, and the four comments-system use cases.
 *
 * <p><strong>This part announces, its use cases do not.</strong> {@link PurgeUserComments} and
 * {@link DeleteThread} RETURN the ids they destroyed and publish nothing, because in the product
 * the announcement has to share the delete's transaction and that transaction belongs to the
 * caller. So the announcing happens here, right after the use case — exactly where
 * {@code CommentsDeletionParticipant} does it in the deployed stack. The memes part's use cases
 * announce for themselves. That asymmetry is the product's, and this suite does not even it out.
 */
public final class CommentsPart {

    private final FakeCommentRepository rows = new FakeCommentRepository();
    private final FakeCommentVotes votes = new FakeCommentVotes();
    private final CommentAnnouncements announcements;

    private final MarkUserCommentsForErasure hide;
    private final PurgeUserComments destroy;
    private final RestoreUserComments bringBack;
    private final DeleteThread dropThread;

    CommentsPart(Clock clock, CommentAnnouncements announcements) {
        this.announcements = announcements;
        this.hide = new MarkUserCommentsForErasure(rows, clock);
        this.destroy = new PurgeUserComments(rows, rows, votes, new PurgeRule.Delete());
        this.bringBack = new RestoreUserComments(rows);
        this.dropThread = new DeleteThread(rows, rows, votes);
    }

    /** The rows, for the steps to seed and to read. */
    public FakeCommentRepository rows() {
        return rows;
    }

    public FakeCommentVotes votes() {
        return votes;
    }

    public void wrote(UserId author, int howMany, String name) {
        for (int i = 1; i <= howMany; i++) {
            rows.wrote(ContentIds.commentOf(name, i), ContentIds.SOMEONE_ELSES_MEME, author);
        }
    }

    public void wroteUnder(String memeId, String commentId, UserId author) {
        rows.wrote(commentId, memeId, author);
    }

    public void upvoted(String commentId, int howManyPeople) {
        for (int i = 1; i <= howManyPeople; i++) {
            votes.cast(commentId, "reader-" + i, VoteDirection.UP);
        }
    }

    public void hide(UserId leaver) {
        hide.execute(leaver);
    }

    public void destroy(UserId leaver, Optional<PurgeRule> condition) {
        PurgeUserComments.Purged purged = destroy.execute(leaver, condition);
        announce(purged.deletedByMeme());
    }

    public void bringBack(UserId leaver) {
        bringBack.execute(leaver);
    }

    /** The thread under a meme that is gone — and the announcement of what went with it. */
    public void dropThreadUnder(String memeId) {
        List<String> dropped = dropThread.execute(memeId);
        if (!dropped.isEmpty()) {
            announcements.commentsDeleted(memeId, dropped);
        }
    }

    private void announce(Map<String, List<String>> deletedByMeme) {
        deletedByMeme.forEach(announcements::commentsDeleted);
    }
}
