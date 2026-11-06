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
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A ballot must not outlive the meme it votes on (P18 poz. 28).
 *
 * <p>Why it matters more than tidiness: the hot page joins ballots to memes on the LEFT and treats a
 * meme with no known publication time as brand new rather than burying it (RankMemes.hotness) —
 * which is the biggest multiplier the ranking has. An orphan ballot therefore does not sit quietly
 * in a table; it puts a meme that cannot be served at the top of the page and keeps it there. After
 * a GDPR purge it is also a leftover of a deleted account's activity.
 *
 * <p>The window is real: {@code CastVote} checks {@code exists(memeId)} and inserts the ballot in a
 * SEPARATE transaction, so a delete committing in between used to produce exactly that row, and
 * nothing looked for it afterwards ({@code purgeMeme} had already run for that meme).
 */
@Epic("Voting")
@Feature("Ballots and their memes")
@SpringBootTest(classes = MemesApplication.class)
class OrphanBallotTest {

    private static final UserId SOMEBODY = UserId.random();

    @Autowired
    MemeRepository memes;

    @Autowired
    VoteRepository votes;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("a meme row deleted by ANY path takes its ballots with it — the schema guarantees it")
    void the_cascade_takes_the_ballots() {
        memes.save(new Meme("cascade-me", SOMEBODY, "png", new byte[]{1}));
        votes.cast("cascade-me", "voter@example.com", VoteDirection.UP);
        assertEquals(1, ballotsFor("cascade-me"), "the ballot is in the table to begin with");

        // deliberately NOT memes.deleteById / PurgeUserContent: those remember to call purgeMeme
        // themselves, so they would prove nothing about what happens when a path forgets. This is
        // the raw row disappearing — a future delete path, a moderator's SQL, a cascade from above.
        jdbc.sql("DELETE FROM memes WHERE id = ?").param("cascade-me").update();

        assertEquals(0, ballotsFor("cascade-me"),
                "the ballot must be gone with its meme, or the hot page ranks a meme nobody can see");
    }

    @Test
    @DisplayName("a vote that loses the race with a delete casts nothing, rather than an orphan")
    void a_vote_that_races_a_delete_is_a_no_op() {
        memes.save(new Meme("vanishing", SOMEBODY, "png", new byte[]{1}));
        // the interleaving CastVote cannot close: its exists() said yes, and the meme goes before
        // the ballot's own transaction runs
        jdbc.sql("DELETE FROM memes WHERE id = ?").param("vanishing").update();

        votes.cast("vanishing", "voter@example.com", VoteDirection.UP);

        assertEquals(0, ballotsFor("vanishing"), "no ballot may be recorded against a meme that is gone");
        assertEquals(0, votes.scoreOf("vanishing"));
    }

    @Test
    @DisplayName("an orphan ballot cannot be written at all — not even straight through SQL")
    void the_database_refuses_an_orphan() {
        assertThrows(RuntimeException.class, () ->
                        jdbc.sql("INSERT INTO meme_votes (meme_id, voter, direction) VALUES (?, ?, ?)")
                                .params("no-such-meme", "voter@example.com", "UP").update(),
                "the foreign key is the floor under the application's own cleanup, not a duplicate of it");
    }

    private int ballotsFor(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM meme_votes WHERE meme_id = ?")
                .param(memeId).query(Integer.class).single();
    }
}
