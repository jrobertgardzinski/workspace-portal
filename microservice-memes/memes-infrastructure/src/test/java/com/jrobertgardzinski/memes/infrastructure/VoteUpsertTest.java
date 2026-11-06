package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.voting.VoteDirection;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two people cannot vote for each other, but ONE person can double-click — and the gallery's vote
 * arrows are not disabled while a request is in flight, so two first-time casts by the same voter
 * on the same meme really do arrive at once. The primary key {@code (meme_id, voter)} makes exactly
 * one of them a row; what this test is about is what the OTHER one gets back.
 *
 * <p>It used to get a 500. The cast was a DELETE followed by an INSERT: the loser's DELETE could
 * not see the winner's uncommitted row, deleted nothing, and its INSERT then landed on the key the
 * winner had already taken — {@code DuplicateKeyException}, handled nowhere, so an unhandled
 * exception for a double-click, with the second click's intended toggle lost on top.
 *
 * <p>The two interleavings are pinned separately because they fail differently: the DELETE that
 * misses the winner's row (simulated by a cast whose {@code retract} does nothing — which is what a
 * DELETE against an invisible row IS), and the MERGE that loses the {@code WHEN NOT MATCHED} race
 * on Postgres, where the answer is one retry down {@code WHEN MATCHED}. Neither can be produced on
 * cue by two real threads against H2, and a test that waits for a race to happen is a test that
 * passes for the wrong reason.
 */
@Epic("Voting")
@Feature("Concurrent first casts")
@SpringBootTest(classes = MemesApplication.class)
class VoteUpsertTest {

    private static final UserId SOMEBODY = UserId.random();

    @Autowired
    MemeRepository memes;

    @Autowired
    VoteRepository votes;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("a cast whose delete missed the winner's row records the vote instead of blowing up")
    void a_cast_that_lost_the_first_vote_race_still_records_a_vote() {
        String memeId = savedMeme();
        votes.cast(memeId, "racer@example.com", VoteDirection.UP);   // the winner, already committed
        // the loser's half of the interleaving: its DELETE ran while the winner's row was still
        // invisible to it, so it removed nothing — and then had to write the same key anyway
        VoteRepository lostTheRace = new JdbcVoteRepository(jdbc) {
            @Override
            public void retract(String memeId, String voter) {
                // deleted nothing, exactly like a DELETE against a row it cannot see yet
            }
        };

        lostTheRace.cast(memeId, "racer@example.com", VoteDirection.DOWN);

        assertEquals(1, ballotsFor(memeId), "the primary key still allows exactly one ballot");
        assertEquals(Optional.of(VoteDirection.DOWN), votes.voteOf(memeId, "racer@example.com"),
                "and the later of the two casts is the one remembered");
    }

    @Test
    @DisplayName("a cast losing the MERGE insert race retries once and lands as WHEN MATCHED")
    void a_cast_retries_once_after_a_concurrent_merge_insert() {
        String memeId = savedMeme();
        // PG15+ MERGE may still raise a unique violation when two first-time casts race WHEN NOT
        // MATCHED; H2 cannot be made to lose that race on cue, so the first pass is forced to fail
        // the way the loser would — the retry then runs the real MERGE
        var firstPassLosesTheRace = new JdbcVoteRepository(jdbc) {
            boolean firstPass = true;

            @Override
            void mergeVote(String id, String voter, VoteDirection direction) {
                if (firstPass) {
                    firstPass = false;
                    throw new DuplicateKeyException("simulated concurrent WHEN NOT MATCHED insert");
                }
                super.mergeVote(id, voter, direction);
            }
        };

        firstPassLosesTheRace.cast(memeId, "racer@example.com", VoteDirection.UP);

        assertEquals(1, ballotsFor(memeId));
        assertEquals(Optional.of(VoteDirection.UP), votes.voteOf(memeId, "racer@example.com"));
    }

    private String savedMeme() {
        String id = UUID.randomUUID().toString();
        memes.save(new Meme(id, SOMEBODY, "png", new byte[]{1}));
        return id;
    }

    private int ballotsFor(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM meme_votes WHERE meme_id = ?")
                .param(memeId).query(Integer.class).single();
    }
}
