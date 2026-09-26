package com.jrobertgardzinski.portal.closure.collections;

import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureInitiator;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.closure.ClosureParticipantContractTest;
import com.jrobertgardzinski.collections.application.MarkUserItemsForErasure;
import com.jrobertgardzinski.collections.application.PurgeUserItems;
import com.jrobertgardzinski.collections.application.RestoreUserItems;
import com.jrobertgardzinski.collections.closure.ClosureOutcome;
import com.jrobertgardzinski.collections.closure.CollectionsClosureParticipant;
import com.jrobertgardzinski.collections.domain.ItemRef;
import com.jrobertgardzinski.collections.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The collections axis: the protocol comes from the contract; the short closure and the ignored rule are this service's own. */
@Epic("Saga")
@Feature("Account closure — the collections axis")
class CollectionsClosureParticipantTest extends ClosureParticipantContractTest {

    private final HeapFavourites favourites = new HeapFavourites();
    private final List<Observation> observed = new ArrayList<>();
    private int saved;
    private int confirmed = -1;   // no confirmation port here: the consumer confirms what Reserved says

    private final CollectionsClosureParticipant participant = new CollectionsClosureParticipant(
            new MarkUserItemsForErasure(favourites, Clock.systemUTC()),
            new RestoreUserItems(favourites),
            new PurgeUserItems(favourites),
            (Observations<Observation>) observed::add);

    @Override
    protected void handle(ClosureCommand command) {
        handled(command);
    }

    private ClosureOutcome handled(ClosureCommand command) {
        ClosureOutcome outcome = participant.handle(command);
        if (outcome instanceof ClosureOutcome.Reserved(int references)) {
            confirmed = references;
        }
        return outcome;
    }

    @Override
    protected void givenLeaverHolds(int rows) {
        for (int i = 0; i < rows; i++) {
            favourites.add(LEAVER, "favourites", new ItemRef("meme", "m" + (++saved)));
        }
    }

    @Override
    protected int confirmed() {
        return confirmed;
    }

    @Override
    protected boolean nothingTouched() {
        return favourites.visibleOf(LEAVER).size() == saved && favourites.heldBy(LEAVER).size() == saved;
    }

    @Override
    protected boolean observedReservedNothing() {
        return observed.stream().anyMatch(o -> o instanceof Observation.PurgeReservedNothing);
    }

    @Test
    @DisplayName("the closure destroys exactly what the mark reserved")
    void mark_then_close() {
        givenLeaverHolds(3);
        assertEquals(new ClosureOutcome.Reserved(3), handled(command(ClosureMessages.PURGE_USER_CONTENT)));
        assertEquals(new ClosureOutcome.Erased(3, 0), handled(command(ClosureMessages.ERASE_USER_CONTENT)));
        assertEquals(0, favourites.heldBy(LEAVER).size());
    }

    @Test
    @DisplayName("the compensation puts back exactly what the mark took out")
    void mark_then_compensate() {
        givenLeaverHolds(2);
        handled(command(ClosureMessages.PURGE_USER_CONTENT));
        assertEquals(new ClosureOutcome.Restored(2), handled(command(ClosureMessages.RESTORE_USER_CONTENT)));
        assertEquals(2, favourites.visibleOf(LEAVER).size());
    }

    @Test
    @DisplayName("a reference saved AFTER the mark is left standing, counted, by the closure")
    void the_closure_can_come_up_short() {
        givenLeaverHolds(2);
        handled(command(ClosureMessages.PURGE_USER_CONTENT));
        givenLeaverHolds(1);   // the offline gate accepts the token until it expires

        assertEquals(new ClosureOutcome.Erased(2, 1), handled(command(ClosureMessages.ERASE_USER_CONTENT)));
        assertTrue(observed.stream().anyMatch(o -> o instanceof Observation.ErasureResidue));
    }

    @Test
    @DisplayName("the command's conditions are never read: this axis has nothing to keep")
    void the_policy_has_no_meaning_here() {
        givenLeaverHolds(1);
        handled(command(ClosureMessages.PURGE_USER_CONTENT));
        handled(command(ClosureMessages.ERASE_USER_CONTENT, LEAVER, ClosureInitiator.ADMIN.wire(),
                "KEEP_POPULAR_ANONYMIZED:10"));
        assertEquals(0, favourites.heldBy(LEAVER).size());
    }
}
