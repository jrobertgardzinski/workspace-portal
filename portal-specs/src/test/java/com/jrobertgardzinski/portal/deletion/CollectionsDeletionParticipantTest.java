package com.jrobertgardzinski.portal.deletion;

import com.jrobertgardzinski.collections.application.PurgeDeletedItem;
import com.jrobertgardzinski.collections.deletion.CollectionsDeletionParticipant;
import com.jrobertgardzinski.deletion.CascadeHopContractTest;
import com.jrobertgardzinski.deletion.CommentsDeleted;
import com.jrobertgardzinski.deletion.DeletionOutcome;
import com.jrobertgardzinski.deletion.MemeDeleted;
import com.jrobertgardzinski.portal.world.FakeFavourites;
import com.jrobertgardzinski.portal.world.Identities;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The collections hop against the cascade's contract, over the fakes. It extends the plain contract
 * and not {@code AtomicHopContractTest}: this hop is the cascade's end and announces nothing, so
 * it has no second write whose fate the first must share.
 */
@Epic("Meme deletion")
@Feature("The collections hop")
class CollectionsDeletionParticipantTest extends CascadeHopContractTest {

    private static final String COMMENT = "5c6d7e8f-9a0b-4c1d-8e2f-3a4b5c6d7e8f";

    private final FakeFavourites favourites = new FakeFavourites();

    private final CollectionsDeletionParticipant service =
            new CollectionsDeletionParticipant(new PurgeDeletedItem(favourites));

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
            favourites.savedPointingAt(Identities.idOf("saver-" + i + "@example.com"), "meme", MEME);
        }
    }

    @Override
    protected boolean nothingTouched() {
        return favourites.pointingAt("meme", MEME).size() == seeded;
    }

    @Test
    @DisplayName("a COMMENTS_DELETED drops every ref to the comments it names")
    void the_second_hop_drops_the_comment_refs() {
        favourites.savedPointingAt(Identities.idOf("saver@example.com"), "comment", COMMENT);

        DeletionOutcome outcome = handle(CommentsDeleted.of(MEME, List.of(COMMENT)).orElseThrow());

        assertEquals(new DeletionOutcome.Dropped(1), outcome);
        assertEquals(List.of(), favourites.pointingAt("comment", COMMENT));
    }
}
