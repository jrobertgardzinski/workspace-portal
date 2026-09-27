package com.jrobertgardzinski.portal.closure.comments;

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
 * The comment service's rows on the heap — a thin subclass of {@link FakeCommentErasure},
 * comments-application's own reference stand-in for {@code CommentErasure}, reached through this
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
public final class HeapComments extends FakeCommentErasure implements CommentRepository {

    private final List<Comment> rows;

    public HeapComments() {
        this(new ArrayList<>());
    }

    private HeapComments(List<Comment> rows) {
        super(rows);
        this.rows = rows;
    }

    /** {@code howMany} comments of one person; the prefix only keeps the ids readable in a failure. */
    public void wrote(String prefix, UserId userId, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            wrote(prefix + "-comment-" + i, userId);
        }
    }

    public void wrote(String id, UserId userId) {
        rows.add(new Comment(id, "someones-meme", Optional.of(userId), "a comment",
                CommentStatus.ACTIVE, null));
    }

    /** Every comment of this person's, marked ones included — what "still on the heap" means. */
    public List<Comment> heldBy(UserId userId) {
        return Stream.concat(activeOf(userId).stream(), pendingOf(userId).stream()).toList();
    }

    /** This person's comments that are actually in a thread right now. */
    public List<Comment> visibleOf(UserId userId) {
        return activeOf(userId);
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

    /** The words an administrator's closure kept: still in the thread, signed by nobody. */
    public List<Comment> signedByNobody() {
        return rows.stream().filter(row -> row.authorId().isEmpty()).filter(row -> !isMarked(row.id())).toList();
    }
}
