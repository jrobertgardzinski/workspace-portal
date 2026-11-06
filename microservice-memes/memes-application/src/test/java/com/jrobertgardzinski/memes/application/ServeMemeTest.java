package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.domain.Meme;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("Use case")
@Feature("Serve meme")
class ServeMemeTest {

    private static final UserId SOMEBODY = UserId.random();

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F'};

    private final MemeRepository memes = new MemeRepository() {
        public void save(Meme meme) { }

        public Optional<Meme> find(String id) {
            return "cat".equals(id)
                    ? Optional.of(new Meme("cat", SOMEBODY, "png", PNG))
                    : Optional.empty();
        }

        public List<String> allIds() { return List.of("cat"); }
        public List<String> findIdsByAuthor(String author) { return List.of(); }
        public void deleteById(String memeId) { }
        public void anonymise(String memeId) { }
    };

    /** A store whose writes always fail — the disk-full / S3-blip case. */
    private final ObjectStore brokenStore = new ObjectStore() {
        public void put(String key, byte[] data) {
            throw new IllegalStateException("store is full");
        }

        public Optional<byte[]> get(String key) { return Optional.empty(); }
        public void delete(String key) { }
    };

    /** A plain in-memory store, so the tests can look at what actually ended up cached. */
    private static ObjectStore mapStore(java.util.Map<String, byte[]> blobs) {
        return new ObjectStore() {
            public void put(String key, byte[] data) {
                blobs.put(key, data);
            }

            public Optional<byte[]> get(String key) {
                return Optional.ofNullable(blobs.get(key));
            }

            public void delete(String key) {
                blobs.remove(key);
            }
        };
    }

    @Test
    @DisplayName("a failed cache write does not cost the caller their WebP")
    void serves_webp_even_when_the_cache_write_fails() {
        ServeMeme serve = new ServeMeme(memes, brokenStore, png -> Optional.of(WEBP));

        Optional<ServeMeme.Image> served = serve.execute("cat", true);

        assertTrue(served.isPresent());
        assertEquals("image/webp", served.get().contentType(),
                "the encoded bytes are in hand — a cache hiccup must not downgrade the response");
        assertArrayEquals(WEBP, served.get().data());
    }

    @Test
    @DisplayName("the encoded WebP is cached for the next request (happy path)")
    void caches_the_encoded_webp() {
        java.util.Map<String, byte[]> blobs = new java.util.HashMap<>();
        ServeMeme serve = new ServeMeme(memes, mapStore(blobs), png -> Optional.of(WEBP));

        Optional<ServeMeme.Image> served = serve.execute("cat", true);

        assertEquals("image/webp", served.orElseThrow().contentType());
        assertArrayEquals(WEBP, blobs.get("cat.webp"),
                "the variant stays cached — the meme still exists, so deleteById will sweep it later");
    }

    @Test
    @DisplayName("a WebP cached for a meme deleted mid-flight is removed again — no orphan")
    void removes_the_variant_cached_for_a_just_deleted_meme() {
        // the race: serve read the meme's bytes, then a concurrent delete removed the meme AND its
        // variants, then serve wrote the freshly encoded WebP — deleteById will never come back for
        // it, so without the re-check the variant would sit in the store forever
        MemeRepository deletedMidFlight = new MemeRepository() {
            private int existenceChecks = 0;

            public Optional<Meme> find(String id) {
                // the read that started this request — the meme was still there
                return Optional.of(new Meme("cat", SOMEBODY, "png", PNG));
            }

            public boolean exists(String id) {
                // the FIRST question is the request's own gate and the meme was still there; by the
                // time the cache write lands and asks again, the delete has committed
                return existenceChecks++ == 0;
            }

            public void save(Meme meme) { }
            public List<String> allIds() { return List.of(); }
            public List<String> findIdsByAuthor(String author) { return List.of(); }
            public void deleteById(String memeId) { }
            public void anonymise(String memeId) { }
        };
        java.util.Map<String, byte[]> blobs = new java.util.HashMap<>();
        ServeMeme serve = new ServeMeme(deletedMidFlight, mapStore(blobs), png -> Optional.of(WEBP));

        Optional<ServeMeme.Image> served = serve.execute("cat", true);

        assertEquals("image/webp", served.orElseThrow().contentType(),
                "the caller still gets the bytes they were promised");
        assertTrue(blobs.isEmpty(), "but the cache write is undone — nothing will ever delete it otherwise");
    }

    @Test
    @DisplayName("a WebP cache hit reads no stored image at all — the row answers, the cache serves")
    void a_cache_hit_never_fetches_the_stored_png() {
        // the hot path: with find() as the existence gate, every request a cached WebP could answer
        // still pulled the full PNG out of MinIO/S3 first and dropped it on the floor
        java.util.List<String> imageReads = new java.util.ArrayList<>();
        MemeRepository counting = new MemeRepository() {
            public Optional<Meme> find(String id) {
                imageReads.add(id);
                return Optional.of(new Meme("cat", SOMEBODY, "png", PNG));
            }

            public boolean exists(String id) { return "cat".equals(id); }

            public void save(Meme meme) { }
            public List<String> allIds() { return List.of("cat"); }
            public List<String> findIdsByAuthor(String author) { return List.of(); }
            public void deleteById(String memeId) { }
            public void anonymise(String memeId) { }
        };
        java.util.Map<String, byte[]> blobs = new java.util.HashMap<>();
        blobs.put("cat.webp", WEBP);
        ServeMeme serve = new ServeMeme(counting, mapStore(blobs), png -> Optional.of(WEBP));

        Optional<ServeMeme.Image> served = serve.execute("cat", true);

        assertEquals("image/webp", served.orElseThrow().contentType());
        assertTrue(imageReads.isEmpty(), "the stored PNG must not be fetched for a hit: " + imageReads);
    }

    @Test
    @DisplayName("a meme whose bytes are gone from the active store serves nothing — but still exists")
    void a_row_without_its_bytes_serves_nothing() {
        // the store-switch state: the row is there, the object is not. Serving is honestly 404,
        // while exists() keeps saying yes — that is what lets /meta, moderation and DELETE work.
        MemeRepository rowWithoutBytes = new MemeRepository() {
            public Optional<Meme> find(String id) { return Optional.empty(); }

            public boolean exists(String id) { return true; }

            public void save(Meme meme) { }
            public List<String> allIds() { return List.of("cat"); }
            public List<String> findIdsByAuthor(String author) { return List.of(); }
            public void deleteById(String memeId) { }
            public void anonymise(String memeId) { }
        };
        ServeMeme serve = new ServeMeme(rowWithoutBytes, mapStore(new java.util.HashMap<>()),
                png -> Optional.of(WEBP));

        assertTrue(serve.execute("cat", false).isEmpty(), "there is no picture to serve");
        assertTrue(serve.execute("cat", true).isEmpty(), "and no encoder can invent one");
    }
}
