package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.FakeVoteRepository;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.RankedMeme;
import com.jrobertgardzinski.voting.VoteDirection;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read a wall of tiles uses to put a number under each thumbnail — and the guard on the
 * distinction the wall got wrong: a score of 0 is a FACT about a meme, and an id nobody can vouch
 * for has NO score at all. Those are two different answers, and this suite pins that the use case
 * never turns the second into the first.
 */
@Epic("Use case")
@Feature("Show meme scores")
class ShowMemeScoresTest {

    private static final UserId SOMEBODY = UserId.random();

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private final Map<String, Meme> memes = new HashMap<>();
    private final Map<String, Map<String, VoteDirection>> votes = new HashMap<>();
    private final AtomicInteger memeReads = new AtomicInteger();
    private final AtomicInteger voteReads = new AtomicInteger();

    private final MemeRepository memeRepository = new FakeMemeRepository(memes) {
        @Override
        public List<String> existingOf(Collection<String> ids) {
            memeReads.incrementAndGet();
            return super.existingOf(ids);
        }
    };
    private final VoteRepository voteRepository = new FakeVoteRepository(votes) {
        @Override
        public Map<String, Integer> scoresOf(Collection<String> memeIds) {
            voteReads.incrementAndGet();
            return super.scoresOf(memeIds);
        }
    };
    private final ShowMemeScores showMemeScores = new ShowMemeScores(memeRepository, voteRepository);

    private void meme(String id) {
        memes.put(id, new Meme(id, SOMEBODY, "png", new byte[]{1}));
    }

    private void voteUp(String memeId, String voter) {
        votes.computeIfAbsent(memeId, id -> new HashMap<>()).put(voter, VoteDirection.UP);
    }

    @Test
    @DisplayName("a meme nobody voted on scores 0 — and SAYS so, with a key of its own")
    void an_unvoted_meme_is_a_known_zero() {
        meme("quiet");

        Map<String, Integer> scores = showMemeScores.execute(List.of("quiet"));

        assertTrue(scores.containsKey("quiet"), "an existing meme must be spoken about");
        assertEquals(0, scores.get("quiet").intValue(), "no ballots is a score of zero, not an absence");
    }

    @Test
    @DisplayName("an id this service has no meme for is ABSENT — never a zero")
    void an_unknown_id_has_no_score() {
        meme("real");
        voteUp("real", "bob");

        Map<String, Integer> scores = showMemeScores.execute(List.of("real", "deleted-long-ago"));

        assertEquals(1, scores.get("real").intValue());
        assertFalse(scores.containsKey("deleted-long-ago"),
                "a missing meme must produce NO entry: a 0 here is the answer 'nobody voted', "
                        + "which is a claim this service cannot make");
    }

    @Test
    @DisplayName("ballots that outlived their meme do not resurrect it into the answer")
    void ballots_without_a_meme_are_not_a_meme() {
        // the vote table can hold rows for a meme the store no longer has (that is why the hot
        // ranking joins LEFT). Existence is the meme store's word, so such an id stays unanswered
        voteUp("ghost", "bob");

        assertEquals(Map.of(), showMemeScores.execute(List.of("ghost")));
    }

    @Test
    @DisplayName("a whole page of tiles costs one read of each store, not one read per tile")
    void a_page_is_two_reads() {
        List<String> page = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            meme("meme-" + i);
            page.add("meme-" + i);
        }

        assertEquals(50, showMemeScores.execute(page).size());
        assertEquals(1, memeReads.get(), "one existence read for the whole page");
        assertEquals(1, voteReads.get(), "one ballot read for the whole page");
    }

    @Test
    @DisplayName("asking about nothing reads nothing")
    void an_empty_question_is_not_a_query() {
        assertEquals(Map.of(), showMemeScores.execute(List.of()));
        assertEquals(0, memeReads.get(), "an empty set of ids must not reach the store");
        assertEquals(0, voteReads.get());
    }

    @Test
    @DisplayName("REGRESSION: the capped hot ranking cannot answer for a wall that outgrew it")
    void the_hot_ranking_is_not_a_score_dictionary() {
        // The paczka-9 regression, pinned at the level where it is provable: /memes/hot is a
        // RANKING capped at TOP_N, so once more memes than that have votes, a client looking a
        // score up in it finds nothing for the ones past the cap — and memes-ui rendered that
        // nothing as "▲ 0" under a meme with votes. The gallery having fewer memes than the cap
        // today is the only reason nobody has seen it yet.
        List<String> wall = new ArrayList<>();
        for (int i = 0; i < RankMemes.TOP_N + 1; i++) {
            String id = "meme-" + i;
            meme(id);
            voteUp(id, "voter-" + i);      // every one of them genuinely has a score of 1
            wall.add(id);
        }

        Set<String> ranked = new RankMemes(voteRepository, Clock.fixed(NOW, ZoneOffset.UTC))
                .execute().stream().map(RankedMeme::memeId).collect(java.util.stream.Collectors.toSet());
        List<String> unrankedButVoted = wall.stream().filter(id -> !ranked.contains(id)).toList();

        assertEquals(RankMemes.TOP_N, ranked.size(), "the ranking is capped, as it should be");
        assertFalse(unrankedButVoted.isEmpty(),
                "a wall bigger than the ranking must contain voted memes the ranking omits — "
                        + "that omission is what a dictionary lookup silently read as zero");

        // the fix: asked directly, every one of those memes has its true score
        Map<String, Integer> scores = showMemeScores.execute(unrankedButVoted);
        unrankedButVoted.forEach(id ->
                assertEquals(1, scores.get(id).intValue(),
                        "the wall's own read knows the score the ranking dropped"));
    }

    /** In-memory {@link MemeRepository} for the use-case tests: ids and authors, no image concerns. */
    static class FakeMemeRepository implements MemeRepository {
        private final Map<String, Meme> memes;

        FakeMemeRepository(Map<String, Meme> memes) {
            this.memes = memes;
        }

        public void save(Meme meme) {
            memes.put(meme.id(), meme);
        }

        public Optional<Meme> find(String id) {
            return Optional.ofNullable(memes.get(id));
        }

        public List<String> allIds() {
            return List.copyOf(memes.keySet());
        }


        public void deleteById(String memeId) {
            memes.remove(memeId);
        }

        public void anonymise(String memeId) {
            memes.computeIfPresent(memeId, (id, m) ->
                    new Meme(m.id(), Optional.empty(), m.format(), m.data()));
        }
    }
}
