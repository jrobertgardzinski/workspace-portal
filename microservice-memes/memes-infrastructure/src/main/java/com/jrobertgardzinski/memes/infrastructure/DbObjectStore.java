package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ObjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The default {@link ObjectStore}: image bytes in their own database table, so durability matches
 * the metadata with no external service. The seam is what matters — swap this bean for the
 * filesystem or the S3 adapter and nothing else in the service changes. (Was {@code @Primary}
 * with no condition, which silently outranked the filesystem adapter even when
 * {@code memes.blob-store=filesystem} — exactly one store bean exists per mode now, pinned by
 * {@code BlobStoreSelectionTest}.)
 */
@Component
@ConditionalOnProperty(name = "memes.blob-store", havingValue = "db", matchIfMissing = true)
class DbObjectStore implements ObjectStore {

    private final JdbcClient jdbc;

    DbObjectStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(String key, byte[] data) {
        jdbc.sql("DELETE FROM meme_blobs WHERE object_key = ?").param(key).update();
        jdbc.sql("INSERT INTO meme_blobs (object_key, data) VALUES (?, ?)").params(key, data).update();
    }

    @Override
    public Optional<byte[]> get(String key) {
        return jdbc.sql("SELECT data FROM meme_blobs WHERE object_key = ?")
                .param(key).query((rs, n) -> rs.getBytes("data")).optional();
    }

    @Override
    public void delete(String key) {
        jdbc.sql("DELETE FROM meme_blobs WHERE object_key = ?").param(key).update();
    }
}
