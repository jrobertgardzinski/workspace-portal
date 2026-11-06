package com.jrobertgardzinski.memes.application;

import java.util.Optional;

/**
 * Port for the raw image bytes, keyed by the meme id — kept separate from the metadata store so
 * the bytes can move to object storage (filesystem, then S3/MinIO) without the meme metadata, the
 * use cases or the domain changing at all. A meme row and its object are written and deleted
 * together by the repository adapter.
 */
public interface ObjectStore {

    void put(String key, byte[] data);

    Optional<byte[]> get(String key);

    void delete(String key);
}
