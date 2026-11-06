package com.jrobertgardzinski.memes.domain;

/**
 * Outbound port for what other services must learn from this one: a deleted meme's comment thread
 * lives in microservice-comments, and it must go when the meme goes.
 */
public interface MemeEvents {

    void memeDeleted(String memeId);
}
