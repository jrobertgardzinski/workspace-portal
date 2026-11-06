package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.config.ImageLimits;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.Meme;
import com.jrobertgardzinski.memes.image.WebImageOptimizer;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("Use case")
@Feature("Publish meme")
class PublishMemeTest {

    private static final UserId ALICE = UserId.random();
    private static final UserId RACER = UserId.random();

    private final Map<String, Meme> store = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> idByContent = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, byte[]> blobs = new java.util.concurrent.ConcurrentHashMap<>();

    private final ObjectStore objects = new ObjectStore() {
        public void put(String key, byte[] data) {
            blobs.put(key, data);
        }

        public Optional<byte[]> get(String key) {
            return Optional.ofNullable(blobs.get(key));
        }

        public void delete(String key) {
            blobs.remove(key);   // like every real adapter: a no-op on a missing key
        }
    };

    private final MemeRepository repository = new MemeRepository() {
        public void save(Meme meme) {
            store.put(meme.id(), meme);
        }

        public Optional<Meme> find(String id) {
            return Optional.ofNullable(store.get(id));
        }

        public List<String> allIds() {
            return List.copyOf(store.keySet());
        }


        public void deleteById(String memeId) {
            store.remove(memeId);
        }

        public void anonymise(String memeId) {
            store.computeIfPresent(memeId, (id, m) ->
                    new Meme(m.id(), Optional.empty(), m.format(), m.data()));
        }
    };
    private final MemeContentIndex contentIndex = new MemeContentIndex() {
        public String claim(byte[] data, String candidateId) {
            String earlier = idByContent.putIfAbsent(key(data), candidateId);
            return earlier != null ? earlier : candidateId;
        }

        public void remove(String memeId) {
            idByContent.values().removeIf(memeId::equals);
        }

        private String key(byte[] data) {
            return Base64.getEncoder().encodeToString(data);
        }
    };
    private final PublishMeme publishMeme =
            new PublishMeme(new WebImageOptimizer(new ImageLimits(1024)), repository, contentIndex, objects);

    @Test
    @DisplayName("publishes an optimized meme")
    void publishes_an_optimized_meme() throws Exception {
        String id = publishMeme.execute(bmp(), ALICE);

        Meme stored = store.get(id);
        assertEquals("png", stored.format());
        assertTrue(stored.data().length > 8);
        assertEquals((byte) 0x89, stored.data()[0]); // PNG magic
    }

    @Test
    @DisplayName("publishing the same image twice reuses the meme (dedup)")
    void deduplicates_identical_uploads() throws Exception {
        byte[] image = bmp();

        String first = publishMeme.execute(image, ALICE);
        String second = publishMeme.execute(image, ALICE);

        assertEquals(first, second);
        assertEquals(1, store.size());
    }

    private static byte[] bmp() throws Exception {
        BufferedImage image = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("a failed save releases the content claim — no ghost for future identical uploads")
    void a_failed_save_releases_the_claim() throws Exception {
        MemeRepository failing = new MemeRepository() {
            public void save(Meme meme) {
                throw new IllegalStateException("store is down");
            }

            public Optional<Meme> find(String id) { return Optional.empty(); }
            public List<String> allIds() { return List.of(); }
            public List<String> findIdsByAuthor(String author) { return List.of(); }
            public void deleteById(String memeId) { }
            public void anonymise(String memeId) { }
        };
        PublishMeme publish =
                new PublishMeme(new WebImageOptimizer(new ImageLimits(1024)), failing, contentIndex, objects);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> publish.execute(bmp(), ALICE));

        assertTrue(idByContent.isEmpty(),
                "the orphaned claim must be compensated, or identical re-uploads dedup into a ghost");
    }

    @Test
    @DisplayName("a save that dies AFTER uploading the bytes leaves no orphaned blob behind")
    void a_failed_save_compensates_the_uploaded_blob() throws Exception {
        // mimics JdbcMemeRepository over S3/filesystem: the bytes reach the ObjectStore from inside
        // the DB transaction, then the transaction fails — the row rolls back, the blob would stay
        MemeRepository failingAfterUpload = new MemeRepository() {
            public void save(Meme meme) {
                objects.put(meme.id(), meme.data());
                throw new IllegalStateException("commit failed after the bytes went up");
            }

            public Optional<Meme> find(String id) { return Optional.empty(); }
            public List<String> allIds() { return List.of(); }
            public List<String> findIdsByAuthor(String author) { return List.of(); }
            public void deleteById(String memeId) { }
            public void anonymise(String memeId) { }
        };
        PublishMeme publish =
                new PublishMeme(new WebImageOptimizer(new ImageLimits(1024)), failingAfterUpload, contentIndex, objects);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> publish.execute(bmp(), ALICE));

        assertTrue(blobs.isEmpty(),
                "no blob (nor any variant) may survive a failed publish — nothing references it, ever");
        assertTrue(idByContent.isEmpty(), "and the content claim is compensated as before");
    }

    @Test
    @DisplayName("when the claim compensation ALSO fails, the original failure surfaces and the blob still goes")
    void a_failing_claim_compensation_neither_masks_the_cause_nor_stops_the_blob_cleanup() throws Exception {
        // the usual shape of this disaster: the DB died, so save fails AND the content-index
        // remove fails the same way — the caller must still see the save's exception (with the
        // remove's pinned as suppressed), and the blob compensation must still run
        MemeRepository failingAfterUpload = new MemeRepository() {
            public void save(Meme meme) {
                objects.put(meme.id(), meme.data());
                throw new IllegalStateException("commit failed after the bytes went up");
            }

            public Optional<Meme> find(String id) { return Optional.empty(); }
            public List<String> allIds() { return List.of(); }
            public List<String> findIdsByAuthor(String author) { return List.of(); }
            public void deleteById(String memeId) { }
            public void anonymise(String memeId) { }
        };
        MemeContentIndex failingRemove = new MemeContentIndex() {
            public String claim(byte[] data, String candidateId) {
                return contentIndex.claim(data, candidateId);
            }

            public void remove(String memeId) {
                throw new IllegalStateException("content index is down too");
            }
        };
        PublishMeme publish = new PublishMeme(
                new WebImageOptimizer(new ImageLimits(1024)), failingAfterUpload, failingRemove, objects);

        IllegalStateException surfaced = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> publish.execute(bmp(), ALICE));

        assertEquals("commit failed after the bytes went up", surfaced.getMessage(),
                "the SAVE's failure is the story — the failed compensation must not replace it");
        assertEquals(1, surfaced.getSuppressed().length, "…but it is not swallowed either");
        assertEquals("content index is down too", surfaced.getSuppressed()[0].getMessage());
        assertTrue(blobs.isEmpty(),
                "the blob compensation must run even though the claim compensation failed");
    }

    @Test
    @DisplayName("two simultaneous uploads of the same picture store exactly one meme")
    void simultaneous_duplicates_store_one_meme() throws Exception {
        byte[] image = bmp();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<String> upload = () -> {
            gate.await();
            return publishMeme.execute(image, RACER);
        };
        var first = executor.submit(upload);
        var second = executor.submit(upload);
        gate.countDown();
        String a = first.get();
        String b = second.get();
        executor.shutdown();

        assertEquals(a, b, "both uploaders end up holding the same meme");
        assertEquals(1, store.size(), "no orphaned copy is stored, even in a dead heat");
    }
}
