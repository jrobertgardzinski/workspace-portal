package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.CastVote;
import com.jrobertgardzinski.memes.application.RankMemes;
import com.jrobertgardzinski.memes.application.ShowMemeScores;
import com.jrobertgardzinski.memes.application.ShowMemeVote;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Web boundary for voting on memes (comment voting lives in microservice-comments). The toggle
 * semantics come from the voting library; responses carry the caller's resulting choice
 * ({@code myVote}), so the UI can render the pressed arrow. The hot list, the batch score read and
 * the tally GET are public.
 */
@RestController
@RequestMapping("/memes")
class VoteController {

    /** What a client posts to vote: {@code direction} is UP or DOWN (case-insensitive). */
    record VoteRequest(String direction) {}

    /**
     * Server policy: how many memes one batch may ask about — a page of the wall
     * ({@code MemeController.MAX_PAGE_SIZE}) and not one more. The endpoint is public and
     * unauthenticated, so the query string must not be a lever for turning one call into an
     * arbitrarily large read.
     */
    private static final int MAX_IDS = 100;

    private final CastVote castVote;
    private final ShowMemeVote showMemeVote;
    private final RankMemes rankMemes;
    private final ShowMemeScores showMemeScores;

    VoteController(CastVote castVote, ShowMemeVote showMemeVote, RankMemes rankMemes,
                   ShowMemeScores showMemeScores) {
        this.castVote = castVote;
        this.showMemeVote = showMemeVote;
        this.rankMemes = rankMemes;
        this.showMemeScores = showMemeScores;
    }

    @PostMapping("/{memeId}/votes")
    ResponseEntity<?> voteOnMeme(@PathVariable("memeId") String memeId,
                                 @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER_ID)
                                 com.jrobertgardzinski.identity.UserId voter,
                                 @RequestBody VoteRequest request) {
        Optional<VoteDirection> direction = parseDirection(request);
        if (direction.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_DIRECTION"));
        }
        // the ballot is keyed by the voter's id, in its wire form
        return toResponse(castVote.execute(memeId, voter.toString(), direction.get()));
    }

    @GetMapping("/{memeId}/votes")
    ResponseEntity<?> memeTally(@PathVariable("memeId") String memeId,
                                @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_USER_ID, required = false)
                                com.jrobertgardzinski.identity.UserId viewer) {
        return toResponse(showMemeVote.execute(memeId, Optional.ofNullable(viewer).map(Object::toString)));
    }

    /**
     * The hot RANKING: the hottest {@link RankMemes#TOP_N} memes, hottest first. An ORDER, and only
     * the head of it — a client must not read it as a lookup table of "the score of meme X",
     * because everything past the cap is missing from it and a lookup that misses looks exactly
     * like a zero. Scores for named memes are {@link #scores} business.
     */
    @GetMapping("/hot")
    List<Map<String, Object>> hot() {
        return rankMemes.execute().stream()
                .map(ranked -> Map.<String, Object>of("memeId", ranked.memeId(), "score", ranked.score()))
                .toList();
    }

    /**
     * The scores of the memes a client is actually showing: {@code ?ids=a,b,c} answers with one
     * entry per meme this service has, capped at {@value #MAX_IDS} ids per call.
     *
     * <p>An id that comes back carries a real score, zero included — a meme nobody voted on scores
     * 0 and says so. An id that does NOT come back is the answer "no tally to report" (this service
     * has no such meme: a deleted one, a mistyped id, a favourite that outlived its meme). The two
     * are different answers on purpose, and a client that flattens the second into a zero prints
     * "0 votes" under memes that have votes.
     */
    @GetMapping("/scores")
    ResponseEntity<?> scores(@RequestParam(name = "ids", required = false) List<String> ids) {
        List<String> asked = (ids == null ? List.<String>of() : ids).stream()
                .filter(id -> !id.isBlank()).toList();
        if (asked.size() > MAX_IDS) {
            return ResponseEntity.badRequest().body(Map.of("status", "TOO_MANY_IDS",
                    "detail", "ask about at most " + MAX_IDS + " memes per call"));
        }
        return ResponseEntity.ok(showMemeScores.execute(asked).entrySet().stream()
                .map(scored -> Map.<String, Object>of("memeId", scored.getKey(), "score", scored.getValue()))
                .toList());
    }

    private static Optional<VoteDirection> parseDirection(VoteRequest request) {
        try {
            return Optional.of(VoteDirection.valueOf(String.valueOf(request.direction()).trim().toUpperCase()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static ResponseEntity<?> toResponse(Optional<VoteTally> tally) {
        if (tally.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("score", tally.get().score());
        body.put("myVote", tally.get().voterChoice().map(Enum::name).orElse(null));
        return ResponseEntity.ok(body);
    }
}
