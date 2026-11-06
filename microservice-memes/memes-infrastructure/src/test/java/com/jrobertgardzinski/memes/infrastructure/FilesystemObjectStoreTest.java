package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filesystem {@link com.jrobertgardzinski.memes.application.ObjectStore} — the stepping stone
 * to S3/MinIO — round-trips bytes and refuses keys that could escape its root.
 *
 * <p>{@link PendingBlobDeletes#none()}: the durability of a DEFERRED delete is
 * {@code PendingBlobDeleteTest}'s subject, and there is no transaction here to defer one into.
 */
@Epic("Image")
@Feature("Object storage")
class FilesystemObjectStoreTest {

    @Test
    @DisplayName("bytes round-trip by key, and a delete removes them")
    void round_trips(@TempDir Path dir) {
        MockEnvironment env = new MockEnvironment().withProperty("memes.blob-dir", dir.toString());
        FilesystemObjectStore store = new FilesystemObjectStore(env, PendingBlobDeletes.none());
        byte[] data = {1, 2, 3, 4};

        store.put("abc123", data);
        assertArrayEquals(data, store.get("abc123").orElseThrow());

        store.delete("abc123");
        assertTrue(store.get("abc123").isEmpty());
        assertTrue(store.get("never-stored").isEmpty());
    }

    @Test
    @DisplayName("a key that could escape the root is refused")
    void refuses_path_traversal(@TempDir Path dir) {
        MockEnvironment env = new MockEnvironment().withProperty("memes.blob-dir", dir.toString());
        FilesystemObjectStore store = new FilesystemObjectStore(env, PendingBlobDeletes.none());
        assertThrows(IllegalArgumentException.class, () -> store.put("../escape", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> store.get("a/b"));
    }
}
