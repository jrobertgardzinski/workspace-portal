package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ObjectStore;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delete of image bytes that live OUTSIDE the database must survive the JVM that promised it
 * (P18 poz. 19).
 *
 * <p>The filesystem and S3 adapters cannot join the DB transaction, so they park the physical delete
 * until after the commit. That parked {@code Runnable} was memory and nothing else: a delete that
 * threw was a WARN with no inventory to retry from, and a pod killed between the commit and the
 * after-commit phase lost the obligation completely — the {@code memes} row that held the key had
 * just been committed away. With {@code MEMES_BLOB_STORE=s3} and a DELETE purge policy that means the
 * saga confirms the purge (durably — its outbox row is in the same transaction), the account is
 * deleted, the leaver is told their content is gone, and their images stay in the bucket for ever.
 *
 * <p>What makes the obligation durable is WHERE it is written: inside the transaction that removes the
 * row. So the three things worth pinning are that it is there at the commit boundary, that a rollback
 * takes it away (or the store would delete the bytes of a meme whose row came back), and that a
 * sweep finishes what the after-commit phase never got to run. The filesystem adapter stands in for
 * S3 here: both go through the same {@code PendingBlobDeletes.deleteDurably}, and the S3 adapter's
 * own test needs a bucket.
 */
@Epic("Infrastructure")
@Feature("Object store")
@SpringBootTest(classes = MemesApplication.class, properties = "memes.blob-store=filesystem")
class PendingBlobDeleteTest {

    @TempDir
    static Path blobDir;

    @DynamicPropertySource
    static void blobDir(DynamicPropertyRegistry registry) {
        registry.add("memes.blob-dir", () -> blobDir.toString());
    }

    @Autowired
    ObjectStore objects;

    @Autowired
    PendingBlobDeletes pending;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @Test
    @DisplayName("the obligation is committed WITH the deletion, and dropped once the bytes are gone")
    void the_obligation_rides_the_transaction() {
        objects.put("owed-1", new byte[]{1, 2, 3});

        tx.executeWithoutResult(status -> {
            objects.delete("owed-1");

            // still inside the transaction: the bytes must NOT be gone yet (a rollback would restore
            // the meme row), but the obligation must already be part of what is about to commit —
            // that is the whole difference between surviving a crash here and not
            assertTrue(objects.get("owed-1").isPresent(), "the bytes wait for the commit");
            assertEquals(1, owed("owed-1"),
                    "the obligation must be written INSIDE this transaction; a crash the instant it "
                            + "commits is exactly the case that used to lose the key for ever");
        });

        assertTrue(objects.get("owed-1").isEmpty(), "the after-commit phase deletes the bytes");
        assertEquals(0, owed("owed-1"), "and the discharged obligation is dropped");
    }

    @Test
    @DisplayName("a rollback takes the obligation with it — the object stays with its row")
    void a_rollback_owes_nothing() {
        objects.put("kept-1", new byte[]{4, 5});

        tx.executeWithoutResult(status -> {
            objects.delete("kept-1");
            status.setRollbackOnly();
        });

        assertEquals(0, owed("kept-1"),
                "an obligation that outlived its rolled-back transaction would have the sweep delete "
                        + "the bytes of a meme whose row is still being served");
        assertTrue(objects.get("kept-1").isPresent(), "the bytes stay with the row that came back");
    }

    @Test
    @DisplayName("a physical delete that FAILS after the commit stays owed")
    void a_failed_delete_stays_owed() throws Exception {
        // a delete the store refuses: the key names a non-empty directory, so deleteIfExists throws.
        // It stands in for the S3 outage the finding is really about — the point is only that the
        // physical delete fails AFTER the transaction has committed, which used to be a WARN and an
        // object nobody could ever find again.
        java.nio.file.Files.createDirectory(blobDir.resolve("stubborn-1"));
        java.nio.file.Files.write(blobDir.resolve("stubborn-1").resolve("inside"), new byte[]{1});

        tx.executeWithoutResult(status -> objects.delete("stubborn-1"));

        assertEquals(1, owed("stubborn-1"),
                "the obligation must outlive the failed attempt — a WARN is not a retry, and there "
                        + "was no other record of the key");
    }

    @Test
    @DisplayName("an obligation nobody discharged is finished by the sweep")
    void the_sweep_finishes_what_the_jvm_did_not() {
        objects.put("lost-1", new byte[]{6, 7});
        // the state a pod killed between the commit and the after-commit phase leaves behind: the row
        // is gone, the bytes are not, and the obligation is the only remaining trace of the key
        owe("lost-1", Duration.ofHours(1));

        int deleted = sweepWithGrace(Duration.ofMinutes(10));

        assertEquals(1, deleted);
        assertTrue(objects.get("lost-1").isEmpty(), "the sweep must finish the delete nobody ran");
        assertEquals(0, owed("lost-1"), "and stop owing it");
    }

    @Test
    @DisplayName("an obligation younger than the grace period is left alone — its transaction may still be open")
    void the_sweep_waits_out_the_grace_period() {
        objects.put("fresh-1", new byte[]{8});
        owe("fresh-1", Duration.ZERO);

        assertEquals(0, sweepWithGrace(Duration.ofMinutes(10)));

        assertTrue(objects.get("fresh-1").isPresent(),
                "a purge of hundreds of memes runs for minutes: deleting the bytes of a transaction "
                        + "that has not committed yet is the rollback bug the parking exists to prevent");
        assertEquals(1, owed("fresh-1"), "the obligation waits for the next pass");
    }

    private int sweepWithGrace(Duration grace) {
        return new PendingBlobDeleteSweep(objects, pending, clock, grace).sweep();
    }

    /** Writes an obligation as if it had been taken on {@code age} ago and never discharged. */
    private void owe(String key, Duration age) {
        jdbc.sql("INSERT INTO pending_blob_deletes (object_key, requested_at) VALUES (?, ?)")
                .params(key, Timestamp.from(clock.instant().minus(age))).update();
    }

    private int owed(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM pending_blob_deletes WHERE object_key = ?")
                .param(key).query(Integer.class).single();
    }
}
