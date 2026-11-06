package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.config.ImageLimits;
import com.jrobertgardzinski.memes.image.OptimizedImage;
import com.jrobertgardzinski.memes.image.WebImageOptimizer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The GLOBAL decode ceiling ({@link ConcurrencyGuardedImageOptimizer}): the per-image bomb guard
 * still admits ~256 MB per decode and the rate limit is per-user, so only a JVM-wide cap stands
 * between N parallel uploads and the heap. With all permits held, the next upload must be refused
 * quickly (429 + Retry-After, the throttling contract uploads already speak) — and once the
 * permits free up, uploads must flow again.
 */
@Epic("Infrastructure")
@Feature("Decode concurrency")
@Story("Global decode ceiling")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class,
        DecodeConcurrencyTest.ThreePermitsAroundABlockingDecode.class})
@AutoConfigureMockMvc
class DecodeConcurrencyTest {

    private static final int PERMITS = 3;
    /** Counted down as each decode ENTERS — proof the permits are actually held. */
    static final CountDownLatch decodesEntered = new CountDownLatch(PERMITS);
    /** Held closed while the fourth upload is refused; opened to let the stuck decodes finish. */
    static final CountDownLatch decodesMayFinish = new CountDownLatch(1);

    @TestConfiguration
    static class ThreePermitsAroundABlockingDecode {

        @Bean
        @Primary
        WebImageOptimizer guardedBlockingOptimizer(ImageLimits limits) {
            // the REAL guard around a stub whose decode blocks on the latch — the stub stands in
            // for a genuinely expensive decode without the test having to craft 8000px images
            WebImageOptimizer blocking = new WebImageOptimizer(limits) {
                @Override
                public OptimizedImage toPngWithin(byte[] input, int maxDimension) {
                    decodesEntered.countDown();
                    try {
                        if (!decodesMayFinish.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test never released the decodes");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return new OptimizedImage(input, "png");
                }
            };
            // short patience so the saturated case answers fast — production waits 5s
            return new ConcurrencyGuardedImageOptimizer(blocking, limits, PERMITS, Duration.ofMillis(300));
        }
    }

    @Autowired
    MockMvc mockMvc;

    private final ExecutorService uploaders = Executors.newFixedThreadPool(PERMITS);

    @AfterEach
    void drain() {
        decodesMayFinish.countDown();
        uploaders.shutdown();
    }

    @Test
    @DisplayName("with every decode permit held, the next upload is refused 429 fast — and flows once permits free up")
    void saturated_decodes_refuse_the_next_upload() throws Exception {
        List<Future<Integer>> stuck = new ArrayList<>();
        for (int i = 0; i < PERMITS; i++) {
            byte[] image = distinctBytes();
            stuck.add(uploaders.submit(() -> mockMvc.perform(multipart("/memes").file(upload(image))
                            .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                    .andReturn().getResponse().getStatus()));
        }
        assertTrue(decodesEntered.await(10, TimeUnit.SECONDS),
                "all " + PERMITS + " decodes must be in flight, holding every permit");

        long refusedAt = System.nanoTime();
        mockMvc.perform(multipart("/memes").file(upload(distinctBytes()))
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value("BUSY"));
        long waitedMillis = Duration.ofNanos(System.nanoTime() - refusedAt).toMillis();
        assertTrue(waitedMillis < 5_000,
                "the refusal must come from the permit timeout (fast), not from queueing behind"
                        + " the stuck decodes — waited " + waitedMillis + " ms");

        decodesMayFinish.countDown();
        for (Future<Integer> upload : stuck) {
            assertEquals(201, upload.get(10, TimeUnit.SECONDS),
                    "the permit-holding uploads must finish normally once released");
        }
    }

    private static MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "m.png", "image/png", bytes);
    }

    private static byte[] distinctBytes() {
        // the blocking stub returns the input as the "optimized" image, so distinct bytes keep
        // the content-hash dedup from collapsing the uploads into one meme
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
