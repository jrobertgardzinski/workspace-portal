package com.jrobertgardzinski.portal.closure.comments;

import com.jrobertgardzinski.closure.AtomicParticipantContractTest;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureInitiator;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.comments.closure.CommentsClosureParticipant;
import com.jrobertgardzinski.comments.domain.DeletedAccount;
import com.jrobertgardzinski.comments.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.purge.PurgeRule;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** The comments axis: the protocol comes from the contract, the purge rule is this service's own. */
@Epic("Saga")
@Feature("Account closure — the comments axis")
class CommentsClosureParticipantTest extends AtomicParticipantContractTest {

    private final HeapComments comments = new HeapComments();
    private final List<Observation> observed = new ArrayList<>();
    private int written;

    private final CommentsClosureParticipant participant = new CommentsClosureParticipant(
            new MarkUserCommentsForErasure(comments, Clock.systemUTC()),
            new RestoreUserComments(comments),
            new PurgeUserComments(comments, comments, mock(CommentVotes.class), new PurgeRule.Delete()),
            confirmations, (Observations<Observation>) observed::add, atomically);

    @Override
    protected void handle(ClosureCommand command) {
        participant.handle(command);
    }

    @Override
    protected void givenLeaverHolds(int rows) {
        for (int i = 0; i < rows; i++) {
            comments.wrote("c" + (++written), LEAVER);
        }
    }

    @Override
    protected void givenLeaverHoldsUnderId(int rows) {
        for (int i = 0; i < rows; i++) {
            comments.wrote("c" + (++written), "old@example.com", LEAVER_ID);
        }
    }

    @Override
    protected boolean nothingTouched() {
        return comments.heldBy(LEAVER).size() == written && comments.visibleOf(LEAVER).size() == written;
    }

    @Override
    protected boolean observedReservedNothing() {
        return observed.stream().anyMatch(o -> o instanceof Observation.PurgeReservedNothing);
    }

    @ParameterizedTest(name = "{0} closure carrying {1}: {2} comments left signed by nobody, the rest deleted")
    @CsvSource({
            "SELF,  ANONYMIZE_AUTHOR,    0",   // the owner's closure admits no conditions
            "ADMIN, ANONYMIZE_AUTHOR,    2",
            "ADMIN, KEEP_THE_FUNNY_ONES, 0",   // unreadable: the deployment's default, not a wedged saga
    })
    void the_rule_is_the_administrators_to_state(ClosureInitiator initiatedBy, String rule, int anonymised) {
        givenLeaverHolds(2);
        handle(command(ClosureMessages.PURGE_USER_CONTENT));
        handle(command(ClosureMessages.ERASE_USER_CONTENT, LEAVER, initiatedBy.wire(), rule));
        assertEquals(0, comments.heldBy(LEAVER).size());
        assertEquals(anonymised, comments.heldBy(DeletedAccount.AUTHOR).size());
    }

    @Test
    @DisplayName("the compensation puts the marked comments back in their threads")
    void restore_undoes_the_mark() {
        givenLeaverHolds(2);
        handle(command(ClosureMessages.PURGE_USER_CONTENT));
        assertEquals(0, comments.visibleOf(LEAVER).size());
        handle(command(ClosureMessages.RESTORE_USER_CONTENT));
        assertEquals(2, comments.visibleOf(LEAVER).size());
    }
}
