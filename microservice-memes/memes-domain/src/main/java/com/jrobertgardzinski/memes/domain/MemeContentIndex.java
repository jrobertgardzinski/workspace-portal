package com.jrobertgardzinski.memes.domain;

/**
 * Port for content-based deduplication: maps the bytes of a stored meme to its id, so uploading
 * the same image twice reuses the existing meme instead of storing a copy. The mapping is claimed
 * ATOMICALLY — two simultaneous uploads of the same picture race for one slot and exactly one
 * wins, so no orphaned copy is ever stored.
 */
public interface MemeContentIndex {

    /** Claim this content for {@code candidateId}; returns the id that OWNS the content — the
     *  candidate when the claim won, the earlier meme's id when the picture was already known. */
    String claim(byte[] data, String candidateId);

    /** Forget a deleted meme, so re-uploading identical content is not deduplicated into a ghost. */
    void remove(String memeId);
}
