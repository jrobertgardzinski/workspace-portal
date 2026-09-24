package com.jrobertgardzinski.portal.closure;

import com.jrobertgardzinski.comments.application.CommentErasure;
import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.domain.Comment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The comment service's rows, on the heap — both ports at once, and {@link #store} writing the
 * erasure columns only, for the same two reasons spelled out in {@link HeapMemes}. This is the
 * service where that second rule earns its keep: an administrator's closure anonymises the author
 * and restores the row in the same loop, so a fake that wrote the whole record back would hand
 * the leaver's name straight back to the thread.
 */
final class HeapComments implements CommentErasure, CommentRepository {

    private final List<Comment> rows = new ArrayList<>();

    void wrote(String author, int howMany) {
        for (int i = 1; i <= howMany; i++) {
            rows.add(new Comment(author + "-comment-" + i, "someones-meme", author, "a comment"));
        }
    }

    List<Comment> heldBy(String author) {
        return rows.stream().filter(row -> row.author().equals(author)).toList();
    }

    List<Comment> visibleOf(String author) {
        return heldBy(author).stream().filter(row -> !row.isPendingErasure()).toList();
    }

    @Override
    public List<Comment> activeOf(String author) {
        return visibleOf(author);
    }

    @Override
    public List<Comment> pendingOf(String author) {
        return heldBy(author).stream().filter(Comment::isPendingErasure).toList();
    }

    @Override
    public void store(Comment state) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(state.id())) {
                Comment held = rows.get(i);
                rows.set(i, new Comment(held.id(), held.memeId(), held.author(), held.text(),
                        state.status(), state.markedForErasureAt()));
                return;
            }
        }
    }

    @Override
    public List<Comment> allUnder(String memeId) {
        return rows.stream().filter(row -> row.memeId().equals(memeId)).toList();
    }

    @Override
    public List<Comment> pendingSince(Instant cutoff) {
        return rows.stream().filter(Comment::isPendingErasure)
                .filter(row -> !row.markedForErasureAt().isAfter(cutoff)).toList();
    }

    @Override
    public void delete(String commentId) {
        rows.removeIf(row -> row.id().equals(commentId));
    }

    @Override
    public void reassignAuthor(String commentId, String newAuthor) {
        for (int i = 0; i < rows.size(); i++) {
            Comment held = rows.get(i);
            if (held.id().equals(commentId)) {
                rows.set(i, new Comment(held.id(), held.memeId(), newAuthor, held.text(),
                        held.status(), held.markedForErasureAt()));
                return;
            }
        }
    }

    @Override
    public List<Comment> findByAuthor(String author) {
        return heldBy(author);
    }

    @Override
    public Optional<Comment> find(String commentId) {
        return rows.stream().filter(row -> row.id().equals(commentId)).findFirst();
    }

    // Writing and reading a thread is another file's story.
    @Override
    public void save(Comment comment) {
        throw new UnsupportedOperationException("writing is not part of closing an account");
    }

    @Override
    public List<Comment> findByMeme(String memeId) {
        return allUnder(memeId);
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
    public void deleteByMeme(String memeId) {
        rows.removeIf(row -> row.memeId().equals(memeId));
    }
}
