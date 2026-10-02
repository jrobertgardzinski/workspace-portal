package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.CommentStatus;
import com.jrobertgardzinski.comments.domain.FakeCommentRepository;
import com.jrobertgardzinski.identity.UserId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The comment service's rows in this process — a thin subclass of {@link FakeCommentRepository},
 * comments-domain's own reference fake for the repository and the erasure axis, reached through
 * this repository's test-jar dependency on it.
 *
 * <p>Nothing of the port is implemented here any more: the active-view semantics of every read,
 * and the status-blindness of {@code deleteByMeme}, live beside the port where the contract test
 * holds them to the adapter. What is left is this runner's own seeding and readers.
 */
public final class FakeComments extends FakeCommentRepository {

    /**
     * {@code howMany} comments of one person, under the ids {@link ContentIds} mints for
     * {@code <prefix>-comment-<n>}. A comment id travels on the cascade's wire too, inside
     * COMMENTS_DELETED, so it is held to the same contract as a meme's.
     */
    public void wrote(String prefix, UserId userId, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            wrote(ContentIds.of(prefix + "-comment-" + i), userId);
        }
    }

    public void wrote(String id, UserId userId) {
        wrote(id, ContentIds.of("someones-meme"), userId);
    }

    /** One comment under a named meme — what the deletion cascade takes a thread to be. */
    public void wroteUnder(String memeId, String id, UserId userId) {
        wrote(id, memeId, userId);
    }

    /** Every comment of this person's, marked ones included — what "the portal still holds" means. */
    public List<Comment> heldBy(UserId userId) {
        return Stream.concat(activeOf(userId).stream(), pendingOf(userId).stream()).toList();
    }

    /** This person's comments that are actually in a thread right now. */
    public List<Comment> visibleOf(UserId userId) {
        return activeOf(userId);
    }

    /**
     * Everything still hanging under a meme, marked rows included. Status-blind on purpose: the
     * cascade's delete is, and a spec that read through the active view would call a thread gone
     * while its marked rows were still there.
     */
    public List<Comment> under(String memeId) {
        return rows.stream().filter(row -> row.memeId().equals(memeId)).toList();
    }

    /** The words an administrator's closure kept: still in the thread, signed by nobody. */
    public List<Comment> signedByNobody() {
        return rows.stream()
                .filter(row -> row.authorId().isEmpty())
                .filter(row -> !isMarked(row.id()))
                .toList();
    }

    /**
     * Every row this fake holds, one canonical line each, sorted — the comment half of
     * {@link Portal#fingerprint()}. The reservation is read as a boolean, for the reason
     * {@link FakeMemes#rows()} gives.
     */
    public List<String> rows() {
        return rows.stream()
                .map(row -> "comment " + row.id() + " under " + row.memeId()
                        + " by " + row.authorId().map(UserId::toString).orElse("nobody")
                        + (isMarked(row.id()) ? " RESERVED" : ""))
                .sorted()
                .toList();
    }

    /** Every id held right now, marked ones included. */
    public List<String> everyId() {
        return rows.stream().map(Comment::id).sorted().toList();
    }

    /**
     * Every row and every mark as they are now, and the way back to them — the comment half of
     * {@link Portal#snapshot()}.
     *
     * <p>A mark whose row the cascade has already deleted is a state this fake really reaches —
     * {@code deleteByMeme} is status-blind, exactly like the adapter — so the way back cannot
     * assume a row for every mark, and builds the record {@link #store} needs out of the id alone.
     */
    public Snapshot snapshot() {
        List<Comment> rowsThen = List.copyOf(rows);
        Map<String, Instant> marksThen = marks();
        return () -> {
            rows.clear();
            rows.addAll(rowsThen);
            Set<String> touched = new HashSet<>(marks().keySet());
            touched.addAll(marksThen.keySet());
            for (String id : touched) {
                Instant marked = marksThen.get(id);
                store(new Comment(id, ContentIds.of("a thread this store never reads"),
                        Optional.empty(), "a comment",
                        marked == null ? CommentStatus.ACTIVE : CommentStatus.PENDING_ERASURE,
                        marked));
            }
        };
    }
}
