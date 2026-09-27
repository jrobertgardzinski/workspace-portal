package com.jrobertgardzinski.portal.deletion;

import com.jrobertgardzinski.comments.application.CommentEvents;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.deletion.CommentsDeletionParticipant;
import com.jrobertgardzinski.deletion.AtomicHopContractTest;
import com.jrobertgardzinski.deletion.CommentsDeleted;
import com.jrobertgardzinski.deletion.DeletionOutcome;
import com.jrobertgardzinski.deletion.MemeDeleted;
import com.jrobertgardzinski.portal.heap.HeapComments;
import com.jrobertgardzinski.portal.heap.Identities;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The comments hop against the cascade's contract, on the heap, with no mock of anything the
 * hop actually decides — the thread is real rows and the announcement is a real call.
 */
@Epic("Meme deletion")
@Feature("The comments hop")
class CommentsDeletionParticipantTest extends AtomicHopContractTest {

    private final HeapComments comments = new HeapComments();

    private final CommentEvents announcer = (memeId, commentIds) -> announcement(memeId);

    private final CommentsDeletionParticipant service = new CommentsDeletionParticipant(
            new DeleteThread(comments, comments, mock(CommentVotes.class)), announcer, unitOfWork);

    private int seeded;

    @Override
    protected DeletionOutcome handle(MemeDeleted memeDeleted) {
        return service.handle(memeDeleted);
    }

    @Override
    protected DeletionOutcome handle(CommentsDeleted commentsDeleted) {
        return service.handle(commentsDeleted);
    }

    @Override
    protected void givenMemeHas(int rows) {
        seeded = rows;
        for (int i = 1; i <= rows; i++) {
            comments.wroteUnder(MEME, MEME + "-comment-" + i,
                    Identities.idOf("commenter-" + i + "@example.com"));
        }
    }

    @Override
    protected boolean nothingTouched() {
        return comments.under(MEME).size() == seeded;
    }

    @Test
    @DisplayName("a COMMENTS_DELETED is not this hop's business: this is where it is produced")
    void the_second_hop_is_not_ours() {
        givenMemeHas(3);
        DeletionOutcome outcome = handle(CommentsDeleted.of(MEME,
                java.util.List.of(MEME + "-comment-1")).orElseThrow());
        assertEquals(new DeletionOutcome.Nothing(), outcome);
        assertEquals(3, comments.under(MEME).size());
    }
}
