package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.voting.Ballots;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The voting context's {@link Ballots} store applied to memes, extended with what only this
 * service needs: the hot ranking and the account-deletion purges.
 */
public interface VoteRepository extends Ballots {

    /**
     * Every meme that has received a vote, with its current score AND its publication time
     * (unordered) — everything {@code RankMemes} needs, in one read. The publication time is part
     * of the answer rather than a follow-up question per meme: that follow-up, asked from inside a
     * comparator, was the hot page's whole cost.
     */
    List<ScoredMeme> allScores();

    /**
     * The ballot tally of EXACTLY these memes — what {@code ShowMemeScores} needs to put a number
     * under every tile of one page without asking per tile.
     *
     * <p>This port reports BALLOTS, so "nobody voted on it" and "no such meme" look identical from
     * here: both are simply an id this answer has nothing for. Whether an id missing from the map
     * means a score of 0 or means "unknown" is decided by the use case, which is the only place
     * that also knows what the meme store says.
     *
     * <p>The per-id fallback keeps hand-rolled fakes working (the same bargain as
     * {@link MemeRepository#exists}); adapters override it with ONE aggregate, because a query per
     * tile is exactly the cost the hot ranking was rewritten to stop paying.
     */
    default Map<String, Integer> scoresOf(Collection<String> memeIds) {
        return memeIds.stream().distinct()
                .collect(Collectors.toMap(Function.identity(), this::scoreOf));
    }

    void purgeMeme(String memeId);

    void purgeVoter(String voter);
}
