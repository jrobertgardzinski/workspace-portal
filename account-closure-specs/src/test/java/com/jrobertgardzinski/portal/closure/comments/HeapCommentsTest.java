package com.jrobertgardzinski.portal.closure.comments;

import com.jrobertgardzinski.comments.domain.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code CommentRepository} axis {@link HeapComments} adds on top of the inherited
 * {@code FakeCommentErasure}: every read must hide a comment a running saga has marked, exactly
 * like the real adapter's {@code active_comments} view — except {@link HeapComments#deleteByMeme},
 * which is status-blind on purpose. The erasure axis itself is not retested here — it is inherited
 * unmodified, and comments-application's own {@code FakeCommentErasureTest} already dogfoods it
 * against the contract.
 */
class HeapCommentsTest {

    private final HeapComments comments = new HeapComments();

    @Test
    @DisplayName("findByMeme, find and findByAuthor hide a comment a running saga has marked")
    void thread_reads_hide_marked_comments() {
        comments.wrote("c1", "author@example.com");
        Comment marked = comments.activeOf("author@example.com").get(0);
        comments.store(marked.markForErasure(Instant.now()));

        assertEquals(0, comments.findByMeme(marked.memeId()).size());
        assertEquals(0, comments.countByMeme(marked.memeId()));
        assertTrue(comments.find("c1").isEmpty());
        assertEquals(0, comments.findByAuthor("author@example.com").size());
    }

    @Test
    @DisplayName("deleteByMeme is status-blind: a marked comment goes with the rest of its thread")
    void cascade_delete_takes_marked_comments_too() {
        comments.wrote("c1", "author@example.com");
        Comment marked = comments.activeOf("author@example.com").get(0);
        comments.store(marked.markForErasure(Instant.now()));

        comments.deleteByMeme(marked.memeId());

        assertEquals(0, comments.heldBy("author@example.com").size());
    }
}
