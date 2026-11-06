package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ObjectStore;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-shot rescue of bytes left in {@code meme_blobs} after the blob store was switched away
 * from {@code db}. The assertions are about the guarantees, not the mechanism: the bytes end up in
 * the ACTIVE store, the legacy table ends up empty, a second run does nothing, one broken object
 * does not take the others (or the startup) with it, and with the DB store still active the table
 * is left strictly alone — it is live data then, and "migrating" it would delete it.
 */
@Epic("Infrastructure")
@Feature("Object store")
class OrphanedBlobMigrationTest {

    private JdbcClient jdbc;
    private Map<String, byte[]> store;

    @BeforeEach
    void freshDatabase() {
        // an isolated H2, same DDL as meme_blobs in V1__schema.sql — no Spring context needed to pin this
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                // DB_CLOSE_DELAY=-1 because this data source hands out a NEW connection per
                // statement — without it the database (and the table) would vanish with the first
                // one that closes
                "jdbc:h2:mem:blobs-" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)
                        + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("create table meme_blobs (object_key varchar(64) primary key, data bytea not null)").update();
        store = new LinkedHashMap<>();
    }

    @Test
    @DisplayName("orphaned bytes move to the active store and the legacy table is emptied")
    void moves_the_bytes_and_empties_the_table() {
        legacyBlob("meme-1", "first picture");
        legacyBlob("meme-2", "second picture");

        int moved = migration("s3", mapStore()).migrate();

        assertEquals(2, moved);
        assertArrayEquals("first picture".getBytes(StandardCharsets.UTF_8), store.get("meme-1"),
                "the bytes themselves, not just the key, are what the meme needs");
        assertArrayEquals("second picture".getBytes(StandardCharsets.UTF_8), store.get("meme-2"));
        assertEquals(0, legacyKeys().size(),
                "and the table is empty: a row nobody reads is a picture no erasure can reach");
    }

    @Test
    @DisplayName("running it again does nothing — a startup task must survive every restart (ADR 0006)")
    void is_idempotent() {
        legacyBlob("meme-1", "first picture");
        OrphanedBlobMigration migration = migration("s3", mapStore());
        assertEquals(1, migration.migrate());

        int movedAgain = migration.migrate();

        assertEquals(0, movedAgain, "nothing left to move");
        assertEquals(1, store.size(), "and nothing written twice");
        assertArrayEquals("first picture".getBytes(StandardCharsets.UTF_8), store.get("meme-1"));
    }

    @Test
    @DisplayName("with the DB store still active the table is left alone — there it IS the store")
    void does_nothing_when_the_db_store_is_the_active_one() {
        legacyBlob("meme-1", "first picture");

        // the property is the whole guard: were it ignored, this "migration" would copy the DB
        // store's live rows onto themselves and then delete them — every image in the service gone
        int moved = migration("db", mapStore()).migrate();

        assertEquals(0, moved);
        assertEquals(List.of("meme-1"), legacyKeys(), "the live table is untouched");
        assertTrue(store.isEmpty());
    }

    @Test
    @DisplayName("one object that cannot be written is skipped, keeps its row, and costs no startup")
    void a_single_failure_neither_stops_the_others_nor_the_service() {
        legacyBlob("meme-1", "first picture");
        legacyBlob("meme-2", "second picture");
        legacyBlob("meme-3", "third picture");
        ObjectStore refusesTheSecond = new ObjectStore() {
            public void put(String key, byte[] data) {
                if ("meme-2".equals(key)) {
                    throw new IllegalStateException("S3 hiccup");
                }
                store.put(key, data);
            }

            public Optional<byte[]> get(String key) { return Optional.ofNullable(store.get(key)); }

            public void delete(String key) { store.remove(key); }
        };

        int moved = migration("s3", refusesTheSecond).migrate();   // must NOT throw

        assertEquals(2, moved, "the healthy objects went across");
        assertEquals(List.of("meme-2"), legacyKeys(),
                "and the failed one keeps its row, so the next start tries again");
    }

    @Test
    @DisplayName("a key the active store already has is not overwritten — the active store is the truth")
    void does_not_overwrite_what_the_active_store_already_holds() {
        legacyBlob("meme-1", "the stale copy");
        store.put("meme-1", "what the active store serves".getBytes(StandardCharsets.UTF_8));

        int moved = migration("s3", mapStore()).migrate();

        assertEquals(0, moved, "nothing was carried over");
        assertArrayEquals("what the active store serves".getBytes(StandardCharsets.UTF_8), store.get("meme-1"));
        assertEquals(0, legacyKeys().size(), "but the legacy row still goes — it is unreachable either way");
    }

    private OrphanedBlobMigration migration(String configuredStore, ObjectStore active) {
        return new OrphanedBlobMigration(jdbc, active, configuredStore);
    }

    private ObjectStore mapStore() {
        return new ObjectStore() {
            public void put(String key, byte[] data) { store.put(key, data); }

            public Optional<byte[]> get(String key) { return Optional.ofNullable(store.get(key)); }

            public void delete(String key) { store.remove(key); }
        };
    }

    private void legacyBlob(String key, String content) {
        jdbc.sql("insert into meme_blobs (object_key, data) values (?, ?)")
                .params(key, content.getBytes(StandardCharsets.UTF_8)).update();
    }

    private List<String> legacyKeys() {
        return jdbc.sql("select object_key from meme_blobs order by object_key")
                .query((rs, n) -> rs.getString("object_key")).list();
    }
}
