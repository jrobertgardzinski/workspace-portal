package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.MemeErasure;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.MemeStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The one adapter in this service allowed to SELECT from {@code memes} itself rather than from the
 * {@code active_memes} view — because it is the one that has to see what the view exists to hide.
 * Everything else reads the gallery ({@link JdbcMemeRepository}), and {@code MemeReadFilterTest}
 * fails the build if that ever stops being true.
 *
 * <p>Three tiny queries, no state of its own, and above all no policy: what a mark means, whether a
 * second mark moves the timestamp, what a restore leaves behind — all of that is decided by
 * {@link MemeMetadata} and merely stored here.
 */
@Repository
class JdbcMemeErasure implements MemeErasure {

    private final JdbcClient jdbc;

    JdbcMemeErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }



    @Override
    public List<MemeMetadata> activeOf(UserId author) {
        return byAuthorId(author, MemeStatus.ACTIVE);
    }

    @Override
    public List<MemeMetadata> pendingOf(UserId author) {
        return byAuthorId(author, MemeStatus.PENDING_ERASURE);
    }

    private List<MemeMetadata> byAuthorId(UserId author, MemeStatus status) {
        return jdbc.sql("SELECT id, author_id, format, status, marked_for_erasure_at "
                        + "FROM memes WHERE author_id = ? AND status = ?")
                .params(author.value(), status.name())
                .query(JdbcMemeErasure::toMetadata).list();
    }


    @Override
    public void store(MemeMetadata state) {
        // the two erasure columns and nothing else: the author id, the format and the publication time
        // are not this port's business, and writing them back would let a stale in-memory copy
        // overwrite a concurrent rename (the anonymisation does exactly that, in this very saga)
        jdbc.sql("UPDATE memes SET status = ?, marked_for_erasure_at = ? WHERE id = ?")
                .params(state.status().name(),
                        state.markedForErasureAt() == null
                                ? null : Timestamp.from(state.markedForErasureAt()),
                        state.id())
                .update();
        // no check on the update count: a meme that vanished between the read and the write (a
        // moderator's takedown races every saga) is not an error — there is simply nothing left to
        // mark or to restore, and the erasure that follows works off a fresh read anyway
    }

    /**
     * Has a running saga reserved this meme? Not on the port: the three saga steps do not ask it —
     * {@link JdbcMemeContentIndex} does, about the meme holding a content hash it wants, and this
     * is the one file allowed to look. "Not in {@code active_memes}" cannot answer it, because a
     * meme that is merely being SAVED is not in the view either.
     */
    boolean isReserved(String memeId) {
        return jdbc.sql("SELECT 1 FROM memes WHERE id = ? AND status = ?")
                .params(memeId, MemeStatus.PENDING_ERASURE.name())
                .query((rs, n) -> 1).optional().isPresent();
    }

    @Override
    public List<MemeMetadata> pendingSince(Instant cutoff) {
        // the reaper's query, in full: a status and an instant, served by idx_memes_pending_erasure.
        // No queue table, no outbox, no scheduler state — the marks ARE the backlog. Ordered by age
        // so the operator reading the alarm sees the oldest obligation first.
        return jdbc.sql("SELECT id, author_id, format, status, marked_for_erasure_at FROM memes "
                        + "WHERE status = ? AND marked_for_erasure_at < ? "
                        + "ORDER BY marked_for_erasure_at")
                .params(MemeStatus.PENDING_ERASURE.name(), Timestamp.from(cutoff))
                .query(JdbcMemeErasure::toMetadata).list();
    }

    private static MemeMetadata toMetadata(ResultSet rs, int rowNum) throws SQLException {
        Timestamp marked = rs.getTimestamp("marked_for_erasure_at");
        return new MemeMetadata(rs.getString("id"), JdbcMemeRepository.authorIdOf(rs),
                rs.getString("format"), MemeStatus.valueOf(rs.getString("status")),
                marked == null ? null : marked.toInstant());
    }
}
