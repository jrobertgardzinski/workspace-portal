package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.voting.VoteTally;
import com.jrobertgardzinski.voting.Voting;

import java.util.Optional;

/**
 * Shows a meme's vote tally through a viewer's eyes (the voting library renders each viewer their
 * own choice, so the UI can press the right arrow). Empty if no such meme.
 */
public class ShowMemeVote {

    private final MemeRepository memeRepository;
    private final Voting voting;

    public ShowMemeVote(MemeRepository memeRepository, VoteRepository voteRepository) {
        this.memeRepository = memeRepository;
        this.voting = new Voting(voteRepository);
    }

    public Optional<VoteTally> execute(String memeId, Optional<String> viewer) {
        // exists(), not find(): the tally is about the row. find() made the anonymous
        // GET /memes/{id}/votes download the whole image from object storage to return a number,
        // and answered 404 for a meme whose bytes are missing from the active store.
        if (!memeRepository.exists(memeId)) {
            return Optional.empty();
        }
        return Optional.of(voting.tally(memeId, viewer));
    }
}
