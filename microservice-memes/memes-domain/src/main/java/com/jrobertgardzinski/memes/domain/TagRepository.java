package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.memes.tags.Tag;

import java.util.Set;

/**
 * Port for the tag index: which tags a meme carries and which memes carry a tag. Tagging is a
 * REPLACE — the author curates the whole set at once — and a deleted meme leaves the index.
 */
public interface TagRepository {

    void replaceTags(String memeId, Set<Tag> tags);

    Set<Tag> tagsOf(String memeId);

    Set<String> memesTagged(Tag tag);

    void removeMeme(String memeId);
}
