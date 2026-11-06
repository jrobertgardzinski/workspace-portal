package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.memes.domain.VoteRepositoryContractTest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The REFERENCE. Everything the contract asks is answered here by the adapter the service actually
 * runs on, against a real database — so "the stand-in behaves like the adapter" means something,
 * instead of meaning "the stand-ins agree with each other".
 *
 * <p>What is NOT here is the SQL's own business: the upsert's race ({@link VoteUpsertTest}), the
 * ballot that may not outlive its meme ({@link OrphanBallotTest}) and the batch read's single
 * round-trip ({@link MemeScoresBatchTest}). This file is only the part a stand-in also has to get
 * right.
 */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
@SpringBootTest(classes = MemesApplication.class)
class JdbcVoteRepositoryTest extends VoteRepositoryContractTest {

    private static final UserId SOMEBODY = UserId.random();

    @Autowired
    VoteRepository votes;

    @Autowired
    MemeRepository memes;

    @Override
    protected VoteRepository votes() {
        return votes;
    }

    @Override
    protected void givenVotableMeme(String memeId) {
        // an ACTIVE meme row, because the adapter's MERGE selects the ballot's target from
        // active_memes: without one, every cast below would quietly write nothing
        memes.save(new Meme(memeId, SOMEBODY, "png", new byte[]{1}));
    }
}
