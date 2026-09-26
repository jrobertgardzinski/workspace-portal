package com.jrobertgardzinski.portal.closure.memes;

import com.jrobertgardzinski.closure.AtomicParticipantContractTest;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureInitiator;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.memes.application.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.application.MemeContentIndex;
import com.jrobertgardzinski.memes.application.MemeEvents;
import com.jrobertgardzinski.memes.application.PurgePolicyOverride;
import com.jrobertgardzinski.memes.application.PurgeUserContent;
import com.jrobertgardzinski.memes.application.RestoreUserContent;
import com.jrobertgardzinski.memes.application.TagRepository;
import com.jrobertgardzinski.memes.application.VoteRepository;
import com.jrobertgardzinski.memes.closure.MemesClosureParticipant;
import com.jrobertgardzinski.memes.domain.DeletedAccount;
import com.jrobertgardzinski.memes.domain.Observation;
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

/** The memes axis: the protocol comes from the contract, the purge rule is this service's own. */
@Epic("Saga")
@Feature("Account closure — the memes axis")
class MemesClosureParticipantTest extends AtomicParticipantContractTest {

    private final HeapMemes memes = new HeapMemes();
    private final List<Observation> observed = new ArrayList<>();
    private int posted;

    private final MemesClosureParticipant participant = new MemesClosureParticipant(
            new MarkUserContentForErasure(memes, Clock.systemUTC()),
            new RestoreUserContent(memes),
            new PurgeUserContent(memes, memes, mock(VoteRepository.class), mock(MemeContentIndex.class),
                    mock(TagRepository.class), mock(MemeEvents.class), mock(PurgePolicyOverride.class),
                    new PurgeRule.Delete()),
            confirmations, (Observations<Observation>) observed::add, atomically);

    @Override
    protected void handle(ClosureCommand command) {
        participant.handle(command);
    }

    @Override
    protected void givenLeaverHolds(int rows) {
        for (int i = 0; i < rows; i++) {
            memes.posted("m" + (++posted), LEAVER);
        }
    }

    @Override
    protected void givenLeaverHoldsUnderId(int rows) {
        for (int i = 0; i < rows; i++) {
            memes.posted("m" + (++posted), "old@example.com", LEAVER_ID);
        }
    }

    @Override
    protected boolean nothingTouched() {
        return memes.heldBy(LEAVER).size() == posted && memes.visibleOf(LEAVER).size() == posted;
    }

    @Override
    protected boolean observedReservedNothing() {
        return observed.stream().anyMatch(o -> o instanceof Observation.PurgeReservedNothing);
    }

    @ParameterizedTest(name = "{0} closure carrying {1}: {2} memes anonymised, the rest deleted")
    @CsvSource({
            "SELF,  ANONYMIZE_AUTHOR,    0",   // the owner's closure admits no conditions
            "ADMIN, ANONYMIZE_AUTHOR,    2",
            "ADMIN, KEEP_THE_FUNNY_ONES, 0",   // unreadable: the deployment's default, not a wedged saga
    })
    void the_rule_is_the_administrators_to_state(ClosureInitiator initiatedBy, String rule, int anonymised) {
        givenLeaverHolds(2);
        handle(command(ClosureMessages.PURGE_USER_CONTENT));
        handle(command(ClosureMessages.ERASE_USER_CONTENT, LEAVER, initiatedBy.wire(), rule));
        assertEquals(0, memes.heldBy(LEAVER).size());
        assertEquals(anonymised, memes.heldBy(DeletedAccount.AUTHOR).size());
    }

    @Test
    @DisplayName("the compensation puts the marked memes back in the gallery")
    void restore_undoes_the_mark() {
        givenLeaverHolds(2);
        handle(command(ClosureMessages.PURGE_USER_CONTENT));
        assertEquals(0, memes.visibleOf(LEAVER).size());
        handle(command(ClosureMessages.RESTORE_USER_CONTENT));
        assertEquals(2, memes.visibleOf(LEAVER).size());
    }
}
