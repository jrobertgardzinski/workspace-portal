package com.jrobertgardzinski.portal.world;

import java.util.UUID;

/**
 * The id the portal would have minted for a meme or a comment. The specs name content in words —
 * "the leaver's first meme", "the cat one" — and one place turns a word into the id the portal
 * actually holds, exactly as {@link Identities} does for a person's address.
 *
 * <p>A UUID and not the readable string it is derived from, because the cascade's wire contract
 * ({@code deletion.Ids}) accepts nothing else: an announcement naming {@code alice-meme-1} is a
 * mis-produced event and every hop drops it. While the closure specs announced into a mock that
 * cost nothing, so the ids stayed readable; the moment the real port went in, a whole account
 * closure announced two deletions that no hop would ever have acted on, and every assertion about
 * the seam passed by announcing nothing. Deriving the id from the word keeps a failure traceable —
 * the same word always gives the same id — without letting the specs invent ids production could
 * not carry.
 */
public final class ContentIds {

    public static String of(String name) {
        return UUID.nameUUIDFromBytes(("content:" + name).getBytes()).toString();
    }

    private ContentIds() {
    }
}
