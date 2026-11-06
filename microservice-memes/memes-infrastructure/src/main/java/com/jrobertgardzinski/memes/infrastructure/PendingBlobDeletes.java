package com.jrobertgardzinski.memes.infrastructure;

import java.time.Instant;
import java.util.List;

/**
 * The register of image bytes this service still OWES a delete on — what makes a delete that cannot
 * join the database transaction survive the JVM that promised it.
 *
 * <p><strong>The hole it closes.</strong> The filesystem and S3 adapters park their physical delete
 * until after the commit ({@link TransactionAwareDeletes}); deleting eagerly would leave a
 * rolled-back meme row pointing at bytes that are already gone. But a parked {@code Runnable} is
 * memory: a delete that threw was a WARN and an orphan nobody could retry (there was no inventory of
 * keys to retry from), and a pod that died between the commit and the after-commit phase lost the
 * obligation entirely — the {@code memes} row that held the key had just been committed away. With
 * {@code MEMES_BLOB_STORE=s3} and a DELETE purge policy the consequence is precise and permanent:
 * the saga's confirmation is durable (an outbox row in the same transaction), so the account is
 * deleted and the leaver is told their content is gone, while their images sit in the bucket forever.
 * {@link OrphanedBlobMigration}'s javadoc calls that identical outcome a GDPR breach.
 *
 * <p><strong>The rule.</strong> The obligation is written INSIDE the transaction that removes the
 * row, so it is exactly as durable and exactly as conditional as that removal: a rollback takes it
 * away (the object must stay — its row came back), a commit keeps it. The row is dropped only after
 * the object is really gone. Anything left in the table is therefore an obligation that was owed and
 * not discharged, and {@link PendingBlobDeleteSweep} finishes it.
 *
 * <p>Not needed by {@link DbObjectStore}: there the bytes ARE database rows, so their deletion is
 * already part of the transaction and cannot be lost separately from it.
 */
interface PendingBlobDeletes {

    /** Takes on the obligation for {@code key}. Idempotent: the same key owed twice is one row. */
    void record(String key);

    /** Drops the obligation — called only once the object is really gone. */
    void forget(String key);

    /** Keys owed since before {@code cutoff}; see the grace period in {@link PendingBlobDeleteSweep}. */
    List<String> overdue(Instant cutoff);

    /**
     * Deletes an object that lives outside the database, durably: journal the obligation in the
     * ambient transaction, run the physical delete after that transaction commits, and forget the
     * obligation only once the delete has returned. Without a transaction there is nothing to park
     * and therefore nothing to lose, so the delete simply runs — the caller still gets the failure.
     *
     * <p>The failure path is the point of the whole class and is deliberately silent here: if
     * {@code physicalDelete} throws, {@code forget} is not reached, the row stays, and the sweep
     * picks it up. {@link TransactionAwareDeletes} logs the throw (it must not let one parked action
     * cut down the others) — what used to be missing was not the log, it was the second attempt.
     */
    default void deleteDurably(String key, Runnable physicalDelete) {
        if (!TransactionAwareDeletes.transactionActive()) {
            physicalDelete.run();
            return;
        }
        record(key);
        TransactionAwareDeletes.afterCommitOrNow(() -> {
            physicalDelete.run();
            forget(key);
        });
    }

    /**
     * A register that remembers nothing — for tests whose subject is the store's round trip rather
     * than the durability of its deletes. {@link #deleteDurably} then behaves exactly as the
     * adapters did before this register existed: park, delete, forget nothing.
     */
    static PendingBlobDeletes none() {
        return new PendingBlobDeletes() {

            @Override
            public void record(String key) {
                // remembers nothing on purpose
            }

            @Override
            public void forget(String key) {
                // remembers nothing on purpose
            }

            @Override
            public List<String> overdue(Instant cutoff) {
                return List.of();
            }
        };
    }
}
