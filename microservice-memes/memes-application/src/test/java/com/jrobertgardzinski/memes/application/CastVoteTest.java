package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.FakeVoteRepository;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("Use case")
@Feature("Cast vote")
class CastVoteTest {

    private static final UserId SOMEBODY = UserId.random();

    private final Map<String, Meme> memes = new HashMap<>();
    private final Map<String, Map<String, VoteDirection>> votes = new HashMap<>();

    private final MemeRepository memeRepository = new MemeRepository() {
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
    };
    private final VoteRepository voteRepository = new FakeVoteRepository(votes);
    private final CastVote castVote = new CastVote(memeRepository, voteRepository);

    @Test
    @DisplayName("the library's toggle applies, anchored to an existing meme")
    void toggles_on_an_existing_meme() {
        memes.put("m1", new Meme("m1", SOMEBODY, "png", new byte[]{1}));

        assertEquals(Optional.of(new VoteTally(1, Optional.of(VoteDirection.UP))),
                castVote.execute("m1", "alice", VoteDirection.UP));
        assertEquals(Optional.of(new VoteTally(0, Optional.empty())),
                castVote.execute("m1", "alice", VoteDirection.UP)); // retracted
    }

    @Test
    @DisplayName("refuses to vote on a missing meme")
    void refuses_missing_meme() {
        assertTrue(castVote.execute("nope", "alice", VoteDirection.UP).isEmpty());
        assertTrue(votes.isEmpty());
    }
}
