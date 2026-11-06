package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import com.jrobertgardzinski.voting.Voting;

import java.util.Optional;

/**
 * Casts a voter's vote on a meme — the toggle semantics come from the voting library; this use
 * case only anchors them to an existing meme. Returns the meme's new tally, or empty if there is
 * no such meme.
 */
public class CastVote {

    private final MemeRepository memeRepository;
    private final Voting voting;

    public CastVote(MemeRepository memeRepository, VoteRepository voteRepository) {
        this.memeRepository = memeRepository;
        this.voting = new Voting(voteRepository);
    }

    public Optional<VoteTally> execute(String memeId, String voter, VoteDirection direction) {
        // exists(), not find(): a vote anchors to the ROW, so the image never has to leave the
        // store for it (find() would fetch the full PNG and drop it — on a public endpoint)
        if (!memeRepository.exists(memeId)) {
            return Optional.empty();
        }
        return Optional.of(voting.toggle(memeId, voter, direction));
    }
}
