package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.RankedMeme;
import com.jrobertgardzinski.memes.domain.ScoredMeme;
import com.jrobertgardzinski.memes.domain.VoteRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks voted memes by HOTNESS: the score cooled by age, Reddit-style — {@code score /
 * (ageInHours + 2)^GRAVITY} while the score is positive, and {@code score ×} that same factor once
 * it is negative, so age moves a meme DOWN whichever way the community voted. A fresh meme with a
 * handful of votes beats last week's champion, which is what a "hot" page is for; with equal ages
 * the plain score still decides. The score itself is reported untouched — decay only orders the
 * page.
 */
public class RankMemes {

    /** How hard age pulls a meme down the page; the classic Reddit exponent. */
    static final double GRAVITY = 1.5;

    /**
     * Server policy: the hot page is a RANKING, not a dump of everything that ever got a vote.
     * A hundred rows is more than any client renders, and the endpoint is public and
     * unauthenticated — the answer's size must not follow the gallery's growth.
     */
    public static final int TOP_N = 100;

    private final VoteRepository voteRepository;
    private final Clock clock;

    public RankMemes(VoteRepository voteRepository, Clock clock) {
        this.voteRepository = voteRepository;
        this.clock = clock;
    }

    public List<RankedMeme> execute() {
        Instant now = clock.instant();
        // hotness is computed ONCE per meme, then sorted on the materialised key. Sorting with
        // Comparator.comparingDouble(meme -> hotness(meme, now)) looks equivalent and is not: the
        // extractor runs at EVERY comparison, so a key that costs anything is paid ~2·n·log2(n)
        // times instead of n. Here the key used to cost a SELECT.
        record Hot(RankedMeme meme, double hotness) {}
        return voteRepository.allScores().stream()
                .map(scored -> new Hot(new RankedMeme(scored.memeId(), scored.score()),
                        hotness(scored, now)))
                .sorted(Comparator.comparingDouble(Hot::hotness).reversed())
                .limit(TOP_N)
                .map(Hot::meme)
                .toList();
    }

    private static double hotness(ScoredMeme meme, Instant now) {
        // no publication time known: treat the meme as brand new rather than burying it — an
        // unknown age must not decide the ranking against a meme that may be perfectly current
        double ageHours = meme.publishedAt()
                .map(published -> Math.max(0.0, Duration.between(published, now).toMillis() / 3_600_000.0))
                .orElse(0.0);
        double decay = Math.pow(ageHours + 2.0, GRAVITY);
        // age has to push a meme DOWN the page whatever the community thought of it, and dividing
        // does that only while the score is positive: a NEGATIVE score divided by a growing
        // denominator climbs towards zero, so the meme ten people rejected a month ago would float
        // above the one rejected once this hour, purely for being older. VoteDirection.DOWN is a
        // first-class part of the API, so a negative score is an ordinary state, not a corner case.
        // Below zero the same decay therefore MULTIPLIES — the one direction that still reads as
        // "further down". Continuous at zero, and unchanged for every score the page is about.
        return meme.score() >= 0 ? meme.score() / decay : meme.score() * decay;
    }
}
