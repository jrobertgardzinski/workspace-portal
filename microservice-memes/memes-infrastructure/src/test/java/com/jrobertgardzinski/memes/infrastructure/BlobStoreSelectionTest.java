package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ObjectStore;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the blob-store switch actually switching: {@code memes.blob-store=filesystem} must select
 * the filesystem adapter. This was silently broken once — the DB store was {@code @Primary} with
 * no condition, so it outranked the conditional filesystem bean and the property did nothing.
 * Exactly one {@link ObjectStore} bean may exist per mode.
 */
@Epic("Infrastructure")
@Feature("Object store")
@SpringBootTest(classes = MemesApplication.class, properties = "memes.blob-store=filesystem")
class BlobStoreSelectionTest {

    @TempDir
    static Path blobDir;

    @DynamicPropertySource
    static void blobDir(DynamicPropertyRegistry registry) {
        registry.add("memes.blob-dir", () -> blobDir.toString());
    }

    @Autowired
    ObjectStore objectStore;

    @Test
    @DisplayName("memes.blob-store=filesystem selects the filesystem adapter, not the DB default")
    void the_switch_switches() {
        assertEquals("FilesystemObjectStore", objectStore.getClass().getSimpleName());
    }
}
