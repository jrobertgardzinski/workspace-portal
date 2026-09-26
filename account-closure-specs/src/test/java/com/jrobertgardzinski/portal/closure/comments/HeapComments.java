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
 * every one of {@link #findByMeme}, {@link #find} and {@link #findByAuthor} hides a comment
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

    public void wrote(String author, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            wrote(author + "-comment-" + i, author);
        }
    }

    /** Rows written after the cutover: the author's id beside the address. */
    public void wrote(String author, UserId authorId, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            wrote(author + "-comment-" + i, author, authorId);
        }
    }

    public void wrote(String id, String author) {
        rows.add(new Comment(id, "someones-meme", author, "a comment"));
    }

    public void wrote(String id, String author, UserId authorId) {
        rows.add(new Comment(id, "someones-meme", author, Optional.of(authorId), "a comment",
                CommentStatus.ACTIVE, null));
    }

    /** Every comment of this author's, marked ones included — what "still on the heap" means. */
    public List<Comment> heldBy(String author) {
        return Stream.concat(activeOf(author).stream(), pendingOf(author).stream()).toList();
    }

    /** This author's comments that are actually in a thread right now. */
    public List<Comment> visibleOf(String author) {
        return activeOf(author);
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
    public List<Comment> findByAuthor(String author) {
        return visibleOf(author);
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
    public void reassignAuthor(String commentId, String newAuthor) {
        for (int i = 0; i < rows.size(); i++) {
            Comment held = rows.get(i);
            if (held.id().equals(commentId)) {
                rows.set(i, new Comment(held.id(), held.memeId(), newAuthor, held.text()));
                return;
            }
        }
    }
}
