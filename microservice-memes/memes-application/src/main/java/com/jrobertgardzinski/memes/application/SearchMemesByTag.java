package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.tags.Tag;

import java.util.List;
import java.util.Set;

/**
 * The gallery filtered by one tag, newest first — the same order the unfiltered gallery uses,
 * narrowed to the memes carrying the tag (and only memes that still exist).
 */
public class SearchMemesByTag {

    private final MemeRepository memes;
    private final TagRepository tags;

    public SearchMemesByTag(MemeRepository memes, TagRepository tags) {
        this.memes = memes;
        this.tags = tags;
    }

    /** One page of the tag's memes, in gallery order — same paging contract as the whole wall. */
    public List<String> execute(Tag tag, long offset, int limit) {
        Set<String> tagged = tags.memesTagged(tag);
        // still a full listing intersected in memory (the tag index and the gallery live in
        // different tables); the page bound at least keeps the RESPONSE from growing without end
        return memes.allIds().stream().filter(tagged::contains)
                .skip(Math.max(0, offset)).limit(Math.max(0, limit)).toList();
    }
}
