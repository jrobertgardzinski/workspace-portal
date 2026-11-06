package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The axis of abuse nobody was watching: how many uploads may hold RAW BYTES at once.
 *
 * <p>{@code file.getBytes()} spends up to the multipart ceiling of heap per request, and the only
 * thing that used to bound the number of requests standing at that line was Tomcat's default of 200
 * threads — 2000 MB against a ~1075 MiB heap. The per-user rate limit is no help: it counts
 * requests over a window, so a single account can have its whole allowance in flight in the same
 * instant. Nor could the decode semaphore be taken earlier, because thumbnails take it too and one
 * thread would hold it twice.
 */
@Epic("Infrastructure")
@Feature("Upload guards")
@Story("Concurrent upload admission")
class UploadAdmissionTest {

    @Test
    @DisplayName("only as many uploads hold bytes at once as the dial allows")
    void the_number_of_uploads_holding_bytes_is_bounded() throws Exception {
        UploadAdmission admission = new UploadAdmission(2, Duration.ofSeconds(5));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger highWaterMark = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(6);

        for (int upload = 0; upload < 6; upload++) {
            Thread.ofVirtual().start(() -> {
                try {
                    admission.admit(() -> {
                        highWaterMark.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        inFlight.decrementAndGet();
                        return "id";
                    });
                } catch (RuntimeException refused) {
                    // a refusal is a legitimate outcome here — what must not happen is admission
                } finally {
                    finished.countDown();
                }
            });
        }

        Thread.sleep(200);
        int concurrent = highWaterMark.get();
        release.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS), "every upload thread settled");

        assertEquals(2, concurrent,
                "six simultaneous uploads must not all be holding their bytes: that is exactly how"
                        + " 200 Tomcat threads times 10 MB overran the heap");
    }

    @Test
    @DisplayName("waiting too long is a 429, not a queue that grows without end")
    void an_upload_that_cannot_get_in_is_refused_rather_than_parked() throws Exception {
        UploadAdmission admission = new UploadAdmission(1, Duration.ofMillis(100));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> admission.admit(() -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "id";
        }));
        assertTrue(holding.await(5, TimeUnit.SECONDS));

        // the client is told to come back rather than being held until it times out on its own
        assertThrows(ImageDecodeOverloadedException.class, () -> admission.admit(() -> "id"));

        release.countDown();
    }

    @Test
    @DisplayName("the permit is released even when the upload itself fails")
    void a_failing_upload_does_not_leak_its_slot() {
        UploadAdmission admission = new UploadAdmission(1, Duration.ofMillis(100));

        assertThrows(IllegalStateException.class, () -> admission.admit(() -> {
            throw new IllegalStateException("the store is down");
        }));

        // a leaked permit would make this second call time out instead of running
        assertEquals("id", admission.admit(() -> "id"));
    }
}
