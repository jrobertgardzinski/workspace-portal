package com.jrobertgardzinski.memes.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Blob stores that live OUTSIDE the database (filesystem, S3) cannot join the DB transaction the
 * delete/purge seam opens ({@link TransactionalDeleteMeme}, {@link TransactionalPurgeUserContent}).
 * Deleting their object eagerly inside that transaction would be wrong in the rollback case: the
 * meme row comes back, its bytes do not. So a delete issued while a transaction is active is
 * parked and runs after the commit; a rollback simply drops it — the object stays with its row.
 * Outside a transaction (e.g. the WebP cache) the delete runs immediately, as before.
 *
 * <p>The same parking serves every commit-dependent side effect of a teardown, not only blob
 * deletes: {@link JdbcMemeRepository} re-sweeps the WebP variant here.
 *
 * <p>Round 10 moved one user out: the MEME_DELETED publication attempt used to park HERE, and now
 * parks on the identical mechanism inside the shared outbox library ({@code AfterCommit} in
 * {@code infrastructure-spring-outbox}, extracted from this class — both traps below included).
 * Two copies of a transaction-synchronisation subtlety is one more than anybody can keep in step,
 * but this one cannot simply delegate to the library: a blob store is not an outbox, and a
 * repository reaching into an outbox library for a filesystem delete would be the worse coupling.
 * <strong>If you fix a bug in either, fix it in both</strong> — the library's version carries the
 * same two paragraphs for the next reader.
 *
 * <p>Two traps this class dodges itself. First: a parked runnable may call back into
 * {@link #afterCommitOrNow} — the repository's after-commit variant re-sweep calls
 * {@code objects.delete}, and on filesystem/S3 that delete parks AGAIN, from inside the
 * after-commit phase, whose synchronization snapshot Spring has already taken. Such a late
 * registration still lands in the live list though, and Spring takes a FRESH snapshot for
 * afterCompletion — so {@link ParkedSideEffect} listens on both phases and runs at whichever
 * comes first (Spring clears the synchronization list before invoking afterCompletion, so a
 * registration attempt from THAT phase falls through to the run-now branch instead of parking
 * into the void). Second (the round-3 finding, fixed in round 5): during the after-commit phase
 * the just-committed transaction still reads as active, but a runnable that opens REQUIRES_NEW
 * inside a callback must have ITS delete parked on the NEW transaction — the registration lands
 * in the new scope automatically and fires at ITS commit (or dies with its rollback). That is
 * why the active-transaction check runs UNCONDITIONALLY, with no "already after commit, run
 * inline" shortcut in front of it: the old shortcut fired such a delete before the new
 * transaction committed, resurrecting the exact bug this class exists to prevent.
 */
final class TransactionAwareDeletes {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionAwareDeletes.class);

    private TransactionAwareDeletes() {
    }

    static void afterCommitOrNow(Runnable delete) {
        if (transactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new ParkedSideEffect(delete));
            return;
        }
        delete.run();
    }

    /**
     * Whether {@link #afterCommitOrNow} would PARK rather than run inline — asked by
     * {@link PendingBlobDeletes#deleteDurably}, which has to journal exactly the deletes that get
     * parked (a parked one can be lost with the JVM; an inline one cannot). Exposed so the condition
     * exists once: two copies of it drifting apart would journal deletes that never park, or park
     * deletes that were never journalled.
     */
    static boolean transactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    /**
     * Best-effort, isolated — like PublishMeme's deleteUploadedBytesBestEffort: once the DB commit
     * has happened, a failing side effect must neither surface out of {@code tx.execute} nor cut
     * down the OTHER parked runnables (Spring stops invoking synchronizations at the first
     * exception). Throwable, not RuntimeException: an Error (OOM in an image buffer, an assertion)
     * would otherwise abort the remaining synchronizations too — including the outbox library's own
     * parked publication, which shares this list. Swallowing an Error is normally taboo, but after
     * the commit there is no caller
     * left to tell; isolation wins, the log gets the truth (at ERROR — an Error is never routine),
     * and a JVM sick enough for the Error to matter will fail the next request on its own.
     *
     * <p>Listens on BOTH after-phases and runs exactly once, at whichever arrives first: a normal
     * registration is served by afterCommit; one made DURING the after-commit phase (invisible to
     * that phase's already-taken snapshot) is caught by afterCompletion, whose snapshot Spring
     * takes fresh. A rollback reaches only afterCompletion, with a non-committed status — the
     * parked side effect is silently dropped, which is the whole point of parking.
     */
    private static final class ParkedSideEffect implements TransactionSynchronization {

        private final Runnable delete;
        private boolean done;

        private ParkedSideEffect(Runnable delete) {
            this.delete = delete;
        }

        @Override
        public void afterCommit() {
            runOnce();
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
                runOnce();
            }
        }

        private void runOnce() {
            if (done) {
                return;
            }
            done = true;
            try {
                delete.run();
            } catch (Error catastrophe) {
                LOG.error("after-commit action failed with an Error — swallowed only so the "
                        + "remaining parked actions still run; the JVM may be unhealthy", catastrophe);
            } catch (Throwable sideEffectFailed) {
                LOG.warn("after-commit action failed — the transaction is committed, "
                        + "continuing with the remaining ones", sideEffectFailed);
            }
        }
    }
}
