package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.application.ObjectStore;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed {@link MemeRepository} (H2 in dev/tests): metadata in the meme row, the image
 * bytes delegated to the {@link ObjectStore} — written and read together, so a meme and its
 * object never drift. The publication time recorded on save is what the hot ranking decays by;
 * the ranking reads it joined onto the vote aggregate, not one meme at a time.
 *
 * <p><strong>Every SELECT below names {@code active_memes}, never {@code memes}</strong> — the view
 * V10 created, whose whole body is {@code WHERE status = 'ACTIVE'}. That is the single place the
 * account-deletion filter is written down in this service: a meme a running saga has marked is
 * absent from the gallery, from {@code /meta}, from the existence checks that gate votes and
 * thumbnails, and from the ids the ranking may speak about, without any of those queries knowing
 * that erasure exists. Writes still name the table, because a view is not what you insert a meme
 * into — and {@code MemeReadFilterTest} enforces exactly that split, so the next SELECT written
 * here cannot quietly become the leak.
 */
@Repository
class JdbcMemeRepository implements MemeRepository {

    private final JdbcClient jdbc;
    private final ObjectStore objects;

    JdbcMemeRepository(JdbcClient jdbc, ObjectStore objects) {
        this.jdbc = jdbc;
        this.objects = objects;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void save(Meme meme) {
        jdbc.sql("INSERT INTO memes (id, author_id, format, published_at) VALUES (?, ?, ?, ?)")
                .params(meme.id(), meme.authorId().map(com.jrobertgardzinski.identity.UserId::value).orElse(null),
                        meme.format(), Timestamp.from(Instant.now()))
                .update();
        objects.put(meme.id(), meme.data());
    }

    @Override
    public Optional<Meme> find(String id) {
        // row AND object: empty when either half is missing, which is why callers that only ask
        // "does it exist" or "who owns it" must use exists()/findMetadata() instead — a meme whose
        // object is absent from the active store is still a meme, and its author must be able to
        // delete it
        return findMetadata(id).flatMap(meta -> objects.get(meta.id()).map(bytes ->
                new Meme(meta.id(), meta.authorId(), meta.format(), bytes)));
    }

    @Override
    public Optional<MemeMetadata> findMetadata(String id) {
        // the row alone — no ObjectStore round trip. The port's default would go through find(),
        // which is exactly the blob read (and the false 404) this method exists to avoid.
        return jdbc.sql("SELECT id, author_id, format FROM active_memes WHERE id = ?")
                .params(id)
                .query((rs, n) -> new MemeMetadata(
                        rs.getString("id"), authorIdOf(rs), rs.getString("format"),
                        com.jrobertgardzinski.memes.domain.MemeStatus.ACTIVE, null))
                .optional();
    }

    @Override
    public boolean exists(String id) {
        // row lookup only — the port's default would drag the blob out of the ObjectStore just to
        // throw it away; ServeMeme calls this on every WebP cache write to spot a concurrent delete
        return jdbc.sql("SELECT 1 FROM active_memes WHERE id = ?")
                .params(id)
                .query((rs, n) -> 1)
                .optional()
                .isPresent();
    }

    @Override
    public List<String> existingOf(java.util.Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();   // "IN ()" is not valid SQL, and an empty question needs no round trip
        }
        // one lookup for the whole set: the port's default would be an exists() per id, which for a
        // page of the wall is a query per tile
        return jdbc.sql("SELECT id FROM active_memes WHERE id IN (:ids)")
                .param("ids", ids.stream().distinct().toList())
                .query((rs, n) -> rs.getString("id")).list();
    }

    @Override
    public List<String> allIds() {
        return jdbc.sql("SELECT id FROM active_memes ORDER BY published_at DESC, id DESC")
                .query((rs, n) -> rs.getString("id")).list();
    }

    @Override
    public List<String> allIds(long offset, int limit) {
        // the page is cut by the database, not by the JVM: the port's default would fetch the
        // whole gallery to hand back fifty ids
        return jdbc.sql("SELECT id FROM active_memes ORDER BY published_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(limit, offset)
                .query((rs, n) -> rs.getString("id")).list();
    }

    /**
     * <strong>The saga's pivot, in the one line that crosses it:</strong> {@code objects.delete}
     * below removes the image from object storage (MinIO/S3 in the deployed stack), and no
     * compensation exists for that — which is why the account-deletion saga only ever reaches this
     * method from the orchestrator's CLOSURE command, once every participant has confirmed its
     * reversible mark. Past this point the saga has one move left, retrying, and V9's
     * {@code pending_blob_deletes} is what makes retrying possible after a crash.
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deleteById(String memeId) {
        jdbc.sql("DELETE FROM memes WHERE id = ?").params(memeId).update();
        objects.delete(memeId);
        // ServeMeme caches a WebP variant under {id}.webp and MakeThumbnail a thumbnail under
        // {id}.thumb; without this sweep the derived copies of a deleted image would sit in the
        // store forever. Every adapter's delete is a no-op on a missing key, so "never cached"
        // costs nothing.
        String variantKey = memeId + ".webp";
        String thumbKey = memeId + ".thumb";
        objects.delete(variantKey);
        objects.delete(thumbKey);
        // On the DB store the sweeps above run INSIDE this transaction, so they miss a variant
        // that a concurrent ServeMeme/MakeThumbnail writes between them and our commit — and
        // that writer's own exists() re-check still sees our uncommitted row, so it leaves the
        // variant in place too. Sweep once more AFTER the commit: any variant put whose commit
        // lands before ours is caught here; one landing after ours is caught by the writer's
        // re-check (the row is then visibly gone). On filesystem/S3 the store's delete PARKS itself again from inside
        // our after-commit callback — TransactionAwareDeletes delivers such late registrations
        // in the afterCompletion phase (a plain afterCommit-only registration would silently
        // never run, and the second sweep would vanish without a trace).
        //
        // On the DB store this after-commit DELETE does NOT get a fresh connection: the
        // just-committed transaction's connection is STILL BOUND to the thread during the
        // after-phases (Spring unbinds it only in the cleanup), so JdbcClient reuses it with
        // autocommit still off — the DELETE becomes durable only when Hikari restores
        // autocommit=true as the connection returns to the pool, which implicitly commits it
        // (probed in round 3). That restore is exactly why spring.datasource.hikari.auto-commit
        // must stay true (the default, pinned by TransactionAwareDeletesTest): with
        // auto-commit=false the pending DELETE would roll back silently instead.
        // two separate parked runnables, not one: TransactionAwareDeletes isolates failures per
        // runnable, so a store hiccup on the WebP sweep cannot cost the thumbnail its sweep
        TransactionAwareDeletes.afterCommitOrNow(() -> objects.delete(variantKey));
        TransactionAwareDeletes.afterCommitOrNow(() -> objects.delete(thumbKey));
    }

    @Override
    public void anonymise(String memeId) {
        // nothing takes the id's place: a kept meme of a closed account belongs to nobody, and
        // cannot be grouped back together by the id of the account that is gone
        jdbc.sql("UPDATE memes SET author_id = NULL WHERE id = ?").param(memeId).update();
    }

    /** Empty for a row written before the id column: the backfill fills those in. */
    static Optional<com.jrobertgardzinski.identity.UserId> authorIdOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.util.UUID id = rs.getObject("author_id", java.util.UUID.class);
        return Optional.ofNullable(id).map(com.jrobertgardzinski.identity.UserId::new);
    }
}
