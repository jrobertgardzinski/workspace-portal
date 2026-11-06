package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.FakeVoteRepository;
import com.jrobertgardzinski.memes.domain.RankedMeme;
import com.jrobertgardzinski.memes.domain.ScoredMeme;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("Use case")
@Feature("Rank memes")
class RankMemesTest {

    private static final Instant NOW = Instant.parse("2026-07-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private RankMemes ranker(ScoredMeme... scored) {
        return new RankMemes(repositoryReturning(List.of(scored), new AtomicInteger()), CLOCK);
    }

    /** A vote store that answers with the given page and counts how often it is asked. */
    private VoteRepository repositoryReturning(List<ScoredMeme> scored, AtomicInteger reads) {
        var votes = new HashMap<String, java.util.Map<String, com.jrobertgardzinski.voting.VoteDirection>>();
        return new FakeVoteRepository(votes) {
            @Override
            public List<ScoredMeme> allScores() {
                reads.incrementAndGet();
                return scored;
            }
        };
    }

    private static ScoredMeme scored(String memeId, int score, Instant publishedAt) {
        return new ScoredMeme(memeId, score, Optional.ofNullable(publishedAt));
    }

    @Test
    @DisplayName("with equal ages the plain score still decides")
    void equal_age_ranks_by_score() {
        List<RankedMeme> ranked = ranker(
                scored("low", 1, NOW), scored("high", 5, NOW), scored("mid", 3, NOW)).execute();
        assertEquals(List.of("high", "mid", "low"), ranked.stream().map(RankedMeme::memeId).toList());
    }

    @Test
    @DisplayName("hot cools with age: a fresh contender outranks last week's champion")
    void freshness_beats_a_stale_high_score() {
        List<RankedMeme> ranked = ranker(
                scored("lastWeeks-champion", 40, NOW.minusSeconds(7 * 24 * 3600)),
                scored("fresh-contender", 5, NOW.minusSeconds(3600))).execute();
        assertEquals("fresh-contender", ranked.get(0).memeId(),
                "40 points from a week ago cool below 5 points from this hour");
        assertEquals(40, ranked.get(1).score(), "the reported score itself is untouched");
    }

    @Test
    @DisplayName("age buries a down-voted meme instead of promoting it")
    void age_never_promotes_a_negative_score() {
        // the decay used to be a plain division, which moves a NEGATIVE score towards zero, i.e. UP
        // a page sorted descending: the meme the gallery rejected ten times a month ago outranked
        // the one it rejected once this hour, and both sat just under a meme nobody voted on
        List<RankedMeme> ranked = ranker(
                scored("rejected-ten-times-a-month-ago", -10, NOW.minusSeconds(30 * 24 * 3600)),
                scored("rejected-once-this-hour", -1, NOW.minusSeconds(3600)),
                scored("nobody-voted", 0, NOW)).execute();

        assertEquals(List.of("nobody-voted", "rejected-once-this-hour", "rejected-ten-times-a-month-ago"),
                ranked.stream().map(RankedMeme::memeId).toList(),
                "age must bury a down-voted meme, never carry it back up the page");
    }

    @Test
    @DisplayName("a meme the store has no publication time for is treated as brand new, not buried")
    void unknown_age_fails_safe() {
        List<RankedMeme> ranked = ranker(
                scored("known-old", 10, NOW.minusSeconds(48 * 3600)),
                scored("unknown", 2, null)).execute();
        assertEquals("unknown", ranked.get(0).memeId());
    }

    @Test
    @DisplayName("ranking a page costs ONE read of the store, not one per comparison")
    void ranking_reads_the_store_once() {
        // the claim this pins: the cost of the hot page grows with the number of memes, not with
        // the number of comparisons the sort happens to make. The old ranking derived its sort key
        // inside Comparator.comparingDouble, and deriving it meant asking the store for the
        // publication time — ~2·n·log2(n) round-trips for n memes (over 4000 reads for the 256
        // below). A per-meme key, materialised before sorting, cannot regress into that shape.
        int memes = 256;
        List<ScoredMeme> page = new ArrayList<>();
        for (int i = 0; i < memes; i++) {
            page.add(scored("meme-" + i, i % 17, NOW.minusSeconds(i * 60L)));
        }
        AtomicInteger reads = new AtomicInteger();

        List<RankedMeme> ranked = new RankMemes(repositoryReturning(page, reads), CLOCK).execute();

        assertEquals(1, reads.get(), "the whole hot page is one read of the vote store");
        assertTrue(ranked.size() <= memes, "ranking invents no memes");
    }

    @Test
    @DisplayName("the hot page is capped at TOP_N — the hottest ones, not everything ever voted on")
    void hot_page_is_capped() {
        List<ScoredMeme> page = new ArrayList<>();
        for (int i = 0; i < RankMemes.TOP_N + 150; i++) {
            // score climbs with i, age is equal: the last TOP_N ids are the hottest
            page.add(scored("meme-" + i, i, NOW));
        }

        List<RankedMeme> ranked = new RankMemes(
                repositoryReturning(page, new AtomicInteger()), CLOCK).execute();

        assertEquals(RankMemes.TOP_N, ranked.size(), "the public hot page has a hard ceiling");
        assertEquals("meme-" + (RankMemes.TOP_N + 149), ranked.get(0).memeId(),
                "the cap keeps the HOTTEST memes, not an arbitrary slice");
    }
}
