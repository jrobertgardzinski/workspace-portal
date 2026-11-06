package com.jrobertgardzinski.memes.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * {@link PendingBlobDeletes} over the {@code pending_blob_deletes} table (V9).
 *
 * <p>The one thing that must not change: every statement here goes through the same
 * {@link JdbcClient} the rest of the service uses, so {@link #record} joins the AMBIENT transaction —
 * the very transaction that removes the meme row. That is the whole guarantee. A register with its
 * own connection would commit obligations for deletions that then rolled back, and the object of a
 * restored meme row would be deleted after all.
 */
@Repository
class JdbcPendingBlobDeletes implements PendingBlobDeletes {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcPendingBlobDeletes(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void record(String key) {
        // delete-then-insert, the portable upsert this portfolio uses everywhere: the same key owed
        // twice must be one row and must never raise a duplicate-key error, because that error would
        // surface INSIDE the purge transaction and take a GDPR deletion down over bookkeeping.
        jdbc.sql("DELETE FROM pending_blob_deletes WHERE object_key = ?").param(key).update();
        jdbc.sql("INSERT INTO pending_blob_deletes (object_key, requested_at) VALUES (?, ?)")
                .params(key, Timestamp.from(clock.instant())).update();
    }

    @Override
    public void forget(String key) {
        jdbc.sql("DELETE FROM pending_blob_deletes WHERE object_key = ?").param(key).update();
    }

    @Override
    public List<String> overdue(Instant cutoff) {
        return jdbc.sql("SELECT object_key FROM pending_blob_deletes WHERE requested_at < ? "
                        + "ORDER BY requested_at")
                .param(Timestamp.from(cutoff))
                .query((rs, n) -> rs.getString("object_key"))
                .list();
    }
}
