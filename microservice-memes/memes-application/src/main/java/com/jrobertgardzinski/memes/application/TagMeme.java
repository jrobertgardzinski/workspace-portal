package com.jrobertgardzinski.memes.application;

import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.memes.config.TagLimits;
import com.jrobertgardzinski.memes.tags.Tag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The author curates their meme's tags: the whole set is replaced in one move (no append drift),
 * capped by {@link TagLimits}. Only the meme's author tags it — a tag says what the WORK is
 * about, and the uploader owns that; anyone else is refused.
 */
public class TagMeme {

    public enum Status { TAGGED, NO_SUCH_MEME, NOT_THE_AUTHOR, TOO_MANY_TAGS }

    public record Result(Status status, Set<Tag> tags) {
        static Result of(Status status) {
            return new Result(status, Set.of());
        }
    }

    private final MemeRepository memes;
    private final TagRepository tags;
    private final TagLimits limits;

    public TagMeme(MemeRepository memes, TagRepository tags, TagLimits limits) {
        this.memes = memes;
        this.tags = tags;
        this.limits = limits;
    }

    /**
     * The caller is an id, not an address: an address moves and is handed on to whoever registers it
     * next, so authorising a curation by one would refuse a renamed author their own meme and let
     * the address's next owner rewrite it.
     *
     * @throws IllegalArgumentException when any raw tag is not a legal {@link Tag}
     */
    public Result execute(String memeId, UserId caller, List<String> rawTags) {
        // findMetadata(), not find(): tagging needs to know who the author is, not what the picture
        // looks like — and a meme whose bytes are missing from the active store must still be
        // curatable by its owner
        var meme = memes.findMetadata(memeId);
        if (meme.isEmpty()) {
            return Result.of(Status.NO_SUCH_MEME);
        }
        if (!meme.get().isOwnedBy(caller)) {
            return Result.of(Status.NOT_THE_AUTHOR);
        }
        Set<Tag> parsed = new LinkedHashSet<>();
        for (String raw : rawTags) {
            parsed.add(Tag.of(raw));
        }
        if (parsed.size() > limits.maxPerMeme()) {
            return Result.of(Status.TOO_MANY_TAGS);
        }
        tags.replaceTags(memeId, parsed);
        return new Result(Status.TAGGED, parsed);
    }
}
