package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.ScoredMeme;
import com.jrobertgardzinski.memes.domain.VoteRepository;
import com.jrobertgardzinski.voting.VoteDirection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Postgres-backed {@link VoteRepository} (H2 in dev/tests): one row per (meme, voter); cast is a
 * MERGE — the upsert this portfolio uses everywhere, and the only shape that survives two
 * first-time casts arriving at once.
 */
@Repository
class JdbcVoteRepository implements VoteRepository {

    private final JdbcClient jdbc;

    JdbcVoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void cast(String memeId, String voter, VoteDirection direction) {
        try {
            mergeVote(memeId, voter, direction);
        } catch (DuplicateKeyException concurrentFirstCast) {
            // Two first-time casts by the same voter can both take WHEN NOT MATCHED, and the loser
            // hits the (meme_id, voter) primary key — 23505. One retry finds the winner's row and
            // goes down WHEN MATCHED, recording this direction as the later of the two casts. The
            // comments twin answers the same race the same way; before this, the loser got an
            // unhandled DuplicateKeyException, i.e. a 500 for a double-click.
            mergeVote(memeId, voter, direction);
        }
    }

    /**
     * The upsert itself — one statement, so nothing of this cast is ever half-done, and a seam the
     * retry test overrides to force the first pass to fail.
     *
     * <p>The source is a SELECT over the meme rather than plain VALUES (which is what the comments
     * twin can afford), because CastVote's exists() check and this write are two separate
     * transactions: a delete or a GDPR purge committing in between used to leave an orphan ballot
     * that nothing ever removed and the hot page promoted to the top (V8 spells the whole scenario
     * out). V8's foreign key is what GUARANTEES no orphan can exist; an empty source here decides
     * what the loser of that race sees — a vote that quietly did not happen on a meme that is gone,
     * rather than a 500 from a constraint violation. active_memes, not memes: a meme a running
     * account-deletion saga has reserved is not something the world may vote on either — and the
     * ballot would then have to be restored (or not) by the compensation, which is a decision
     * nobody should have to make.
     */
    void mergeVote(String memeId, String voter, VoteDirection direction) {
        jdbc.sql("MERGE INTO meme_votes USING "
                        + "(SELECT m.id AS meme_id, CAST(? AS VARCHAR) AS voter, "
                        + "CAST(? AS VARCHAR) AS direction FROM active_memes m WHERE m.id = ?) AS src "
                        + "ON meme_votes.meme_id = src.meme_id AND meme_votes.voter = src.voter "
                        + "WHEN MATCHED THEN UPDATE SET direction = src.direction "
                        + "WHEN NOT MATCHED THEN INSERT (meme_id, voter, direction) "
                        + "VALUES (src.meme_id, src.voter, src.direction)")
                .params(voter, direction.name(), memeId).update();
    }

    @Override
    public void retract(String memeId, String voter) {
        jdbc.sql("DELETE FROM meme_votes WHERE meme_id = ? AND voter = ?")
                .params(memeId, voter).update();
    }

    @Override
    public Optional<VoteDirection> voteOf(String memeId, String voter) {
        return jdbc.sql("SELECT direction FROM meme_votes WHERE meme_id = ? AND voter = ?")
                .params(memeId, voter)
                .query((rs, n) -> VoteDirection.valueOf(rs.getString("direction")))
                .optional();
    }

    @Override
    public int scoreOf(String memeId) {
        return jdbc.sql("SELECT COALESCE(SUM(CASE WHEN direction = 'UP' THEN 1 ELSE -1 END), 0) "
                        + "FROM meme_votes WHERE meme_id = ?")
                .params(memeId)
                .query((rs, n) -> rs.getInt(1)).single();
    }

    @Override
    public List<ScoredMeme> allScores() {
        // published_at rides along on the aggregate instead of being fetched per meme: the hot
        // page needs score AND age, and asking for the age one meme at a time (from inside a
        // comparator, no less) cost more round-trips than the table has rows.
        //
        // INNER, and against active_memes — which is a change from the LEFT JOIN this query used
        // to do, for a reason the erasure status made unavoidable. The use case reads a missing
        // publication time as "brand new" (an unknown age must not bury a current meme), so a
        // ballot whose meme is merely RESERVED by a running saga would have arrived here with a
        // null age and been ranked straight to the top of a public page — the leaver's content,
        // promoted by the very act of deleting their account. The old tolerance protected against
        // orphan ballots, which V8's foreign key has made impossible since; the ranking now speaks
        // only about memes the gallery actually has, exactly like ShowMemeScores.
        return jdbc.sql("SELECT v.meme_id, "
                        + "SUM(CASE WHEN v.direction = 'UP' THEN 1 ELSE -1 END) AS score, "
                        + "m.published_at "
                        + "FROM meme_votes v JOIN active_memes m ON m.id = v.meme_id "
                        + "GROUP BY v.meme_id, m.published_at")
                .query((rs, n) -> {
                    Timestamp published = rs.getTimestamp("published_at");
                    return new ScoredMeme(rs.getString("meme_id"), rs.getInt("score"),
                            published == null ? null : published.toInstant());
                })
                .list();
    }

    @Override
    public Map<String, Integer> scoresOf(Collection<String> memeIds) {
        if (memeIds.isEmpty()) {
            return Map.of();   // "IN ()" is not valid SQL, and an empty question needs no round trip
        }
        // ONE aggregate for a whole page of tiles: the port's per-id default would be a query per
        // thumbnail. A meme with no ballot rows is not in this answer — the use case is what turns
        // that into a score of 0, and only for memes it has confirmed exist.
        return jdbc.sql("SELECT meme_id, SUM(CASE WHEN direction = 'UP' THEN 1 ELSE -1 END) AS score "
                        + "FROM meme_votes WHERE meme_id IN (:ids) GROUP BY meme_id")
                .param("ids", memeIds.stream().distinct().toList())
                .query((rs, n) -> Map.entry(rs.getString("meme_id"), rs.getInt("score")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void purgeMeme(String memeId) {
        jdbc.sql("DELETE FROM meme_votes WHERE meme_id = ?").params(memeId).update();
    }

    @Override
    public void purgeVoter(String voter) {
        jdbc.sql("DELETE FROM meme_votes WHERE voter = ?").params(voter).update();
    }
}
