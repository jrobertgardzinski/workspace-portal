package com.jrobertgardzinski.memes.domain;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;

/**
 * The stand-in the closure specs run on, held to the same promises as the real adapter — including
 * the ones the account closure leans on: the leaver's ballots are retracted BEFORE any score is
 * read, and a mock answers all of that with zero.
 */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
class FakeVoteRepositoryTest extends VoteRepositoryContractTest {

    private final FakeVoteRepository votes = new FakeVoteRepository();

    @Override
    protected VoteRepository votes() {
        return votes;
    }

    @Override
    protected void givenVotableMeme(String memeId) {
        // nothing to record: this stand-in is ballots and no meme store, so every id is votable
        // as far as it knows. Which memes the world may vote on is the adapter's SQL and the
        // schema's foreign key — see the contract's note, and OrphanBallotTest for the guard.
    }
}
