package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Finishes the blob deletes this service still owes: whatever is left in {@code pending_blob_deletes}
 * (V9) is, by construction, an obligation that was taken on inside a committed transaction and never
 * discharged — the physical delete threw, or the pod died between the commit and the after-commit
 * phase that was going to run it.
 *
 * <p><strong>The grace period is not politeness, it is correctness.</strong> An obligation is written
 * BEFORE its transaction commits, so a fresh row may belong to a transaction that is still open — a
 * purge of hundreds of memes runs for minutes. Deleting its bytes now would resurrect exactly the bug
 * the after-commit parking exists to prevent: a rollback restores the meme row and the image is
 * already gone. So only rows older than {@link #DEFAULT_GRACE} are touched, which is comfortably past
 * the longest transaction this service can open (a purge is bounded by the listener's poll interval,
 * 300s). Sweeping late costs nothing; sweeping early destroys data.
 *
 * <p><strong>Two triggers, on purpose.</strong> The schedule handles the delete that failed while the
 * service kept running. {@link ApplicationRunner} handles the case the schedule cannot: a pod that
 * died mid-obligation may not come back, and {@code @EnableScheduling} in this service hangs off
 * {@code memes.kafka-enabled} ({@link MemeOutboxConfig}), so without a broker the schedule does not
 * run at all — a start is then the only pass there is. Both call the same idempotent method; both
 * deletes are no-ops on a key the store no longer has.
 *
 * <p>Reusing {@link ObjectStore#delete} rather than reaching into the bucket directly: outside a
 * transaction that call runs the physical delete inline and journals nothing (see
 * {@link PendingBlobDeletes#deleteDurably}), so the sweep cannot re-register the obligation it is
 * discharging. It also means the sweep works for whichever store is active without knowing which.
 *
 * <p>No PII in the logs: keys are meme ids and their variants, never authors.
 */
@Component
class PendingBlobDeleteSweep implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PendingBlobDeleteSweep.class);

    /**
     * How long an obligation must have stood before the sweep may act on it. Ten minutes: the
     * transaction that wrote it has to be over first, and the longest one this service opens is a
     * purge on the Kafka listener thread, bounded by {@code max.poll.interval.ms} (300s).
     */
    static final Duration DEFAULT_GRACE = Duration.ofMinutes(10);

    private final ObjectStore objects;
    private final PendingBlobDeletes pending;
    private final Clock clock;
    private final Duration grace;

    @Autowired   // two constructors: Spring must be told which one is the container's
    PendingBlobDeleteSweep(ObjectStore objects, PendingBlobDeletes pending, Clock clock,
                           @Value("${memes.blob-deletes.grace-seconds:600}") long graceSeconds) {
        this(objects, pending, clock, Duration.ofSeconds(graceSeconds));
    }

    PendingBlobDeleteSweep(ObjectStore objects, PendingBlobDeletes pending, Clock clock, Duration grace) {
        this.objects = objects;
        this.pending = pending;
        this.clock = clock;
        this.grace = grace;
    }

    @Override
    public void run(ApplicationArguments args) {
        sweep();
    }

    @Scheduled(fixedDelayString = "${memes.blob-deletes.sweep-interval-ms:60000}")
    void sweepOnSchedule() {
        sweep();
    }

    /** @return how many objects this pass deleted (0 when nothing was owed) */
    int sweep() {
        List<String> owed;
        try {
            owed = pending.overdue(clock.instant().minus(grace));
        } catch (DataAccessException unreadable) {
            // same judgement as OrphanedBlobMigration: a register we cannot read is loud, not fatal —
            // refusing to start (or killing the scheduler thread) would trade a data problem for an
            // outage, and the obligations keep waiting either way
            LOG.error("could not read pending_blob_deletes — owed image deletes are still owed", unreadable);
            return 0;
        }
        if (owed.isEmpty()) {
            return 0;   // the normal case
        }
        LOG.warn("{} image object(s) were owed a delete and did not get one — finishing them now", owed.size());
        int deleted = 0;
        for (String key : owed) {
            try {
                objects.delete(key);
                // only after the store has answered: the row IS the obligation, and dropping it on a
                // failed delete is how the bytes became unreachable in the first place
                pending.forget(key);
                deleted++;
            } catch (RuntimeException stillFailing) {
                LOG.error("could not delete owed image object {} — it stays owed for the next pass",
                        key, stillFailing);
            }
        }
        LOG.warn("owed image deletes finished: {} of {} object(s) gone", deleted, owed.size());
        return deleted;
    }
}
