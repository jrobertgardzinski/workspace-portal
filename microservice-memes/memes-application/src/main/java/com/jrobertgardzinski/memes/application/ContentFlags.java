package com.jrobertgardzinski.memes.application;

import java.util.Set;

/**
 * Moderation flags laid OVER the memes rather than into them: a meme's bytes and authorship are
 * the uploader's, whether it is safe for work is the community's call. Today one axis (NSFW);
 * storage cleans a meme's flags up with the meme itself.
 */
public interface ContentFlags {

    void setNsfw(String memeId, boolean nsfw);

    boolean isNsfw(String memeId);

    /** Every flagged meme in one go — the gallery listing marks members without N queries. */
    Set<String> nsfwIds();
}
