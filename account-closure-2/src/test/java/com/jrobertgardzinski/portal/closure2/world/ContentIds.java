package com.jrobertgardzinski.portal.closure2.world;

/**
 * The id a piece of content is held under. The feature file names content in words — "alice's
 * first meme", "the comment bob saved" — and this is the one place a word becomes an id.
 *
 * <p>Readable, because nothing here puts an id on a wire: the parts hear about each other by a
 * method call in this process, and what carries the fact between them in production is the
 * decision this suite is written before. A failing assertion therefore names the content the way
 * the feature file does.
 */
public final class ContentIds {

    public static String memeOf(String person, int which) {
        return person + "-meme-" + which;
    }

    public static String commentOf(String person, int which) {
        return person + "-comment-" + which;
    }

    /** The meme alice's comments hang under: somebody else's, so her closure never touches it. */
    public static final String SOMEONE_ELSES_MEME = "a-stranger-meme";

    private ContentIds() {
    }
}
