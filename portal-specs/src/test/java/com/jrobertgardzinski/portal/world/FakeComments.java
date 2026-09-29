package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.comments.domain.CommentStatus;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.application.FakeCommentErasure;
import com.jrobertgardzinski.comments.domain.Comment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The comment service's rows in this process — a thin subclass of {@link FakeCommentErasure},
 * comments-application's own reference fake for {@code CommentErasure}, reached through this
 * repository's test-jar dependency on it. What is added here is the {@code CommentRepository} axis
 * over the SAME backing list, and the convenience readers the specs already call by name.
 *
 * <p>{@code CommentRepository}'s reads mirror the real adapter's {@code active_comments} view:
 * every one of {@link #findByMeme} and {@link #find} hides a comment
 * {@link #isMarked} still remembers. Only {@link #deleteByMeme} is status-blind, exactly like the
 * real adapter's cascade delete — a marked comment goes with the rest of its thread. The original,
 * fully self-contained version of this class got exactly this wrong: {@code findByMeme} read
 * through {@code allUnder}, which does not filter, and nothing here exercised the difference.
 */
public final class FakeComments extends FakeCommentErasure implements CommentRepository {

    private final List<Comment> rows;

    public FakeComments() {
        this(new ArrayList<>());
    }

    private FakeComments(List<Comment> rows) {
        super(rows);
        this.rows = rows;
    }

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
        rows.add(new Comment(id, ContentIds.of("someones-meme"), Optional.of(userId), "a comment",
                CommentStatus.ACTIVE, null));
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
     * Every row this fake holds, one canonical line each, sorted — the comment half of
     * {@link com.jrobertgardzinski.portal.world.Portal#fingerprint()}. The reservation is read as
     * a boolean, for the reason {@link FakeMemes#rows()} gives.
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

    // writing a comment is not part of closing an account
    @Override
    public void save(Comment comment) {
        throw new UnsupportedOperationException("writing is not part of closing an account");
    }

    @Override
    public List<Comment> findByMeme(String memeId) {
        return rows.stream()
                .filter(row -> row.memeId().equals(memeId))
                .filter(row -> !isMarked(row.id()))
                .toList();
    }

    @Override
    public List<Comment> findByMeme(String memeId, int offset, int limit) {
        return findByMeme(memeId).stream().skip(offset).limit(limit).toList();
    }

    @Override
    public int countByMeme(String memeId) {
        return findByMeme(memeId).size();
    }

    @Override
    public Optional<Comment> find(String commentId) {
        return rows.stream()
                .filter(row -> row.id().equals(commentId))
                .filter(row -> !isMarked(commentId))
                .findFirst();
    }

    @Override
    public void delete(String commentId) {
        rows.removeIf(row -> row.id().equals(commentId));
    }

    @Override
    public void deleteByMeme(String memeId) {
        rows.removeIf(row -> row.memeId().equals(memeId));
    }

    @Override
    public void anonymise(String commentId) {
        for (int i = 0; i < rows.size(); i++) {
            Comment held = rows.get(i);
            if (held.id().equals(commentId)) {
                rows.set(i, new Comment(held.id(), held.memeId(), Optional.empty(), held.text(),
                        CommentStatus.ACTIVE, null));
                return;
            }
        }
    }

    /** One comment under a named meme — what the deletion cascade takes a thread to be. */
    public void wroteUnder(String memeId, String id, UserId userId) {
        rows.add(new Comment(id, memeId, Optional.of(userId), "a comment",
                CommentStatus.ACTIVE, null));
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
        return rows.stream().filter(row -> row.authorId().isEmpty()).filter(row -> !isMarked(row.id())).toList();
    }
}
