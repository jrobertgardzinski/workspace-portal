package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.voting.VoteDirection;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link VoteRepository}: ballots in a map, keyed by meme and then by voter, so a
 * test can give a meme a score instead of asserting against a store that has none.
 *
 * <p>Public, and this module's own test-jar publishes it, for the reason {@link FakeMemeErasure}
 * is: a consumer that needs a score — the account-closure specs one repository up, where
 * {@code KEEP_POPULAR_ANONYMIZED} is decided — should not have to write its own, and MUST NOT
 * reach for a mock. A mocked {@code scoreOf} answers 0 for ever, which silently makes the only
 * rule that reads a score unstatable and hides the ordering {@code PurgeUserContent} depends on:
 * the leaver's own ballots are retracted BEFORE any score is read, and a mock cannot tell the
 * difference.
 *
 * <p>The score is the sum of the ballots and nothing else; there is no separate counter to fall
 * out of step with them, which is the one promise every caller here leans on.
 */
public class FakeVoteRepository implements VoteRepository {

    private final Map<String, Map<String, VoteDirection>> votes;

    /** Over ballots the test also inspects directly. */
    public FakeVoteRepository(Map<String, Map<String, VoteDirection>> votes) {
        this.votes = votes;
    }

    /** Over ballots nobody else looks at. */
    public FakeVoteRepository() {
        this(new HashMap<>());
    }

    @Override
    public void cast(String memeId, String voter, VoteDirection direction) {
        votes.computeIfAbsent(memeId, id -> new HashMap<>()).put(voter, direction);
    }

    @Override
    public void retract(String memeId, String voter) {
        votes.getOrDefault(memeId, Map.of()).remove(voter);
    }

    @Override
    public Optional<VoteDirection> voteOf(String memeId, String voter) {
        return Optional.ofNullable(votes.getOrDefault(memeId, Map.of()).get(voter));
    }

    @Override
    public int scoreOf(String memeId) {
        return votes.getOrDefault(memeId, Map.of()).values().stream()
                .mapToInt(direction -> direction == VoteDirection.UP ? 1 : -1).sum();
    }

    @Override
    public List<ScoredMeme> allScores() {
        // memes with no ballot LEFT are left out, exactly as the adapter's aggregate leaves them
        // out: a retraction deletes the row there, while here it leaves an empty tally behind, and
        // a meme reported at 0 by one implementation and not at all by the other is the drift
        // VoteRepositoryContractTest exists to catch. The ranking reads this answer, and an
        // implementation that kept them would put memes nobody votes on above ones voted down.
        return votes.entrySet().stream()
                .filter(meme -> !meme.getValue().isEmpty())
                .map(meme -> new ScoredMeme(meme.getKey(), scoreOf(meme.getKey()),
                        Optional.<Instant>empty()))
                .toList();
    }

    @Override
    public void purgeMeme(String memeId) {
        votes.remove(memeId);
    }

    @Override
    public void purgeVoter(String voter) {
        votes.values().forEach(ballots -> ballots.remove(voter));
    }
}
