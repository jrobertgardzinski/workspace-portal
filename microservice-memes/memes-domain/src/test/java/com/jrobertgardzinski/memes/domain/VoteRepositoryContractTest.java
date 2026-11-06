package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.voting.VoteDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link VoteRepository} promises, asked of EVERY implementation — the JDBC adapter the
 * service runs on and each stand-in used in its place. A stand-in that drifts does not fail; it
 * makes a green suite say something about a service that does not exist, which is worse than a
 * mock, because it is convincingly wrong.
 *
 * <p>These are not arbitrary promises: they are what {@code PurgeUserContent} leans on when it
 * retracts the leaver's ballots and only then reads each meme's score, and what
 * {@code ShowMemeScores} and {@code RankMemes} lean on when they ask for a page of scores in one
 * go. {@code KEEP_POPULAR_ANONYMIZED} — the one {@code PurgeRule} that reads a score — had no
 * scenario anywhere in the portal while the specs ran on {@code mock(VoteRepository.class)}, whose
 * {@code scoreOf} answers 0 for ever; the stand-in is what made that rule statable, so the
 * stand-in's arithmetic is now load-bearing.
 *
 * <p>Fresh ids AND fresh voter names per test method, because one implementation is a database
 * other suites share: {@code purgeVoter} is keyed by the voter alone, across every meme in the
 * store, and {@link VoteRepository#allScores()} answers about the whole of it. Every assertion
 * below therefore speaks only about this run's own ids.
 *
 * <p><b>What this contract deliberately does NOT state</b>, so that the three known asymmetries
 * are named in one place instead of discovered:
 * <ul>
 *   <li><b>Casting on a meme the world may not vote on.</b> The adapter's MERGE selects from
 *       {@code active_memes}, so a ballot on a deleted meme — or on one a running closure saga has
 *       reserved — quietly does not happen; the in-memory stand-in has no meme store to ask and
 *       records it. That guard is the schema's and the SQL's, and it is pinned where it lives, by
 *       {@code OrphanBallotTest} against a real database. The contract only requires that the
 *       implementation be TOLD which memes are votable, through {@link #givenVotableMeme}.</li>
 *   <li><b>The publication time in {@link VoteRepository#allScores()}.</b> The adapter reads it off
 *       the meme row; a stand-in over ballots alone has none and answers empty, which the ranking
 *       reads as "brand new". No stand-in is therefore fit to rank a gallery, and none is asked to
 *       — {@code RankMemes} is specified against the adapter.</li>
 *   <li><b>Whether an unvoted id is absent from {@link VoteRepository#scoresOf} or present with
 *       0.</b> The port says so in as many words, and leaves it to the use case, which is the only
 *       place that also knows what the meme store says. The contract asks for the one thing both
 *       answers agree on: nothing that is read as a score other than 0.</li>
 * </ul>
 */
public abstract class VoteRepositoryContractTest {

    private final String run = java.util.UUID.randomUUID().toString().substring(0, 8);
    private final String first = "m1-" + run;
    private final String second = "m2-" + run;
    private final String third = "m3-" + run;
    private final String alice = "alice-" + run;
    private final String bob = "bob-" + run;
    private final String carol = "carol-" + run;

    protected abstract VoteRepository votes();

    /** Make this id one the world may vote on, however this implementation records that. */
    protected abstract void givenVotableMeme(String memeId);

    /** This run's own row out of an answer about the whole store. */
    private Optional<Integer> scoreIn(List<ScoredMeme> all, String memeId) {
        return all.stream().filter(scored -> scored.memeId().equals(memeId))
                .map(ScoredMeme::score).findFirst();
    }

    @Test
    @DisplayName("the score is the ballots, and nothing else counts them")
    protected void the_score_is_the_ballots() {
        givenVotableMeme(first);

        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(first, bob, VoteDirection.UP);
        votes().cast(first, carol, VoteDirection.DOWN);

        // up-voters minus down-voters, read back from the ballots themselves: no separate counter
        // to fall out of step with them, which is the promise every caller here leans on
        assertEquals(1, votes().scoreOf(first));
        assertEquals(0, votes().scoreOf(second), "a meme with no ballots scores 0, not nothing");
    }

    @Test
    @DisplayName("one voter has one ballot, and a second cast replaces the first")
    protected void a_second_cast_replaces_the_first() {
        givenVotableMeme(first);
        votes().cast(first, alice, VoteDirection.UP);

        votes().cast(first, alice, VoteDirection.DOWN);

        // the arrows toggle, so this is the ordinary path and not a corner: an implementation that
        // appended instead of replacing would let one person carry a meme
        assertEquals(Optional.of(VoteDirection.DOWN), votes().voteOf(first, alice));
        assertEquals(-1, votes().scoreOf(first));
    }

    @Test
    @DisplayName("retracting takes that voter's ballot and nobody else's")
    protected void retracting_takes_only_that_voters_ballot() {
        givenVotableMeme(first);
        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(first, bob, VoteDirection.UP);

        votes().retract(first, alice);

        assertEquals(Optional.empty(), votes().voteOf(first, alice));
        assertEquals(1, votes().scoreOf(first));

        votes().retract(first, alice);   // a retraction nobody cast is a no-op, not a failure
        assertEquals(1, votes().scoreOf(first));
    }

    @Test
    @DisplayName("a purged meme keeps none of its ballots")
    protected void purging_a_meme_forgets_its_ballots() {
        givenVotableMeme(first);
        votes().cast(first, alice, VoteDirection.UP);

        votes().purgeMeme(first);

        assertEquals(0, votes().scoreOf(first));
        assertTrue(votes().voteOf(first, alice).isEmpty());
    }

    @Test
    @DisplayName("a purged voter leaves every meme at once, and the scores say so")
    protected void purging_a_voter_moves_every_score() {
        givenVotableMeme(first);
        givenVotableMeme(second);
        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(second, alice, VoteDirection.UP);
        votes().cast(second, bob, VoteDirection.UP);

        votes().purgeVoter(alice);

        // the point of the ordering in PurgeUserContent: a leaver cannot buy his own meme's
        // survival with a ballot that is leaving with him
        assertEquals(0, votes().scoreOf(first));
        assertEquals(1, votes().scoreOf(second));
        assertTrue(votes().voteOf(second, alice).isEmpty());
    }

    @Test
    @DisplayName("a page of scores agrees with the same scores read one by one")
    protected void a_page_of_scores_agrees_with_the_reads_one_by_one() {
        givenVotableMeme(first);
        givenVotableMeme(second);
        givenVotableMeme(third);
        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(third, alice, VoteDirection.DOWN);

        Map<String, Integer> page = votes().scoresOf(List.of(first, second, third));

        assertEquals(1, page.get(first));
        assertEquals(-1, page.get(third));
        // the adapter leaves an unvoted id out and the port's per-id default puts it in at 0; what
        // both promise is that it is never read as anything else
        assertEquals(0, page.getOrDefault(second, 0));
        assertEquals(Map.of(), votes().scoresOf(List.of()),
                "an empty question needs no answer, and no round trip");
    }

    @Test
    @DisplayName("every meme with a ballot is in allScores, with its score — and only those")
    protected void all_scores_names_every_meme_with_a_ballot() {
        givenVotableMeme(first);
        givenVotableMeme(second);
        givenVotableMeme(third);
        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(first, bob, VoteDirection.DOWN);
        votes().cast(third, carol, VoteDirection.UP);

        assertEquals(Optional.of(0), scoreIn(votes().allScores(), first),
                "a meme whose ballots cancel out is voted on, and scores 0");
        assertEquals(Optional.empty(), scoreIn(votes().allScores(), second),
                "a meme nobody voted on is not part of this answer at all");

        votes().retract(third, carol);

        // and neither is one whose last ballot went: the hot page ranks what this answer names, so
        // a meme left in it at 0 by an implementation that keeps empty tallies would be promoted
        // over memes that have actually been voted down
        assertEquals(Optional.empty(), scoreIn(votes().allScores(), third));
    }
}
