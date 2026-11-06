package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The vote scores of a NAMED set of memes — the read a wall of tiles needs: one round trip for the
 * whole page, instead of one per thumbnail.
 *
 * <p>The distinction this use case exists to preserve is ZERO versus UNKNOWN. A meme this service
 * still has is answered with its score, and a meme nobody voted on scores 0 — that is a fact worth
 * printing. A meme this service does not have is simply ABSENT from the answer: no key, no number,
 * nothing to render. A client can then say "▲ 0" in the first case and "no tally" in the second,
 * which it can only do if the answer keeps the two apart.
 *
 * <p>Why that is worth a use case of its own: the wall used to read its numbers out of
 * {@link RankMemes}, whose page is a RANKING capped at {@link RankMemes#TOP_N}. Every meme past the
 * cap was missing from that list, and a client looking its id up found nothing — which it rendered
 * as a confident zero. "0 votes" under a meme that has votes is not a smaller lie for being an
 * accident, and it was waiting for the gallery to outgrow the cap to become visible.
 */
public class ShowMemeScores {

    private final MemeRepository memeRepository;
    private final VoteRepository voteRepository;

    public ShowMemeScores(MemeRepository memeRepository, VoteRepository voteRepository) {
        this.memeRepository = memeRepository;
        this.voteRepository = voteRepository;
    }

    /**
     * Scores keyed by meme id, for the ids this service has a meme for; every other id asked about
     * is absent from the map — that absence is the answer "I have no tally to report", and callers
     * must render it as such rather than substituting a zero.
     */
    public Map<String, Integer> execute(Collection<String> memeIds) {
        if (memeIds.isEmpty()) {
            return Map.of();   // nothing asked, nothing read: an empty set is not a query
        }
        List<String> known = memeRepository.existingOf(memeIds);
        Map<String, Integer> ballots = voteRepository.scoresOf(known);
        // an existing meme with no ballot rows is a KNOWN zero, and the filling-in happens HERE:
        // all a vote store can honestly report is which memes have ballots — that "no ballots"
        // means "score 0" is this use case's reading of it, not the adapter's invention
        Map<String, Integer> scores = new LinkedHashMap<>();
        known.forEach(memeId -> scores.put(memeId, ballots.getOrDefault(memeId, 0)));
        return scores;
    }
}
