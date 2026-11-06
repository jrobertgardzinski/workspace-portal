package com.jrobertgardzinski.memes.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * A voted meme as the store hands it over: its current score together with when it went up — the
 * two inputs of hotness, read in ONE go. They travel together on purpose: asking the store for the
 * publication time separately is what turned ranking a page into a round-trip per comparison.
 *
 * <p>An empty {@code publishedAt} means the store has no publication time for this meme (a ballot
 * that outlived its meme row); the ranking decides what that means, the store does not guess.
 */
public record ScoredMeme(String memeId, int score, Optional<Instant> publishedAt) {

    public ScoredMeme(String memeId, int score, Instant publishedAt) {
        this(memeId, score, Optional.ofNullable(publishedAt));
    }
}
