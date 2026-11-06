package com.jrobertgardzinski.memes.system;

import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.memes.domain.MemeContentIndex;
import com.jrobertgardzinski.memes.domain.MemeMetadata;
import com.jrobertgardzinski.memes.domain.MemeRepository;
import com.jrobertgardzinski.memes.domain.TagRepository;
import com.jrobertgardzinski.memes.domain.VoteRepository;

import java.util.Optional;

/**
 * Take one meme down completely: its votes, its content-index claim, its tags, the row itself, and
 * an announcement so the comment service drops the thread. WHO may do this (the author, or a
 * moderator acting on someone else's) is the boundary's call; this use case is the teardown and
 * whether there was anything to tear down.
 */
public class DeleteMeme {

    public enum Status { DELETED, NO_SUCH_MEME }

    public record Result(Status status) {}

    private final MemeRepository memes;
    private final VoteRepository votes;
    private final MemeContentIndex contentIndex;
    private final TagRepository tags;
    private final MemeEvents memeEvents;

    public DeleteMeme(MemeRepository memes, VoteRepository votes, MemeContentIndex contentIndex,
                      TagRepository tags, MemeEvents memeEvents) {
        this.memes = memes;
        this.votes = votes;
        this.contentIndex = contentIndex;
        this.tags = tags;
        this.memeEvents = memeEvents;
    }

    public Result execute(String memeId) {
        // findMetadata(), not find(): a teardown needs the author (the caller is told whose meme
        // went), never the picture. find() read the full image out of object storage to get one
        // string — and, worse, reported NO_SUCH_MEME for a meme whose object is missing from the
        // active store, so its own author could not take it down. Deletion must reach every meme
        // the gallery lists; the bytes are removed by id further down, present or not.
        Optional<MemeMetadata> meme = memes.findMetadata(memeId);
        if (meme.isEmpty()) {
            return new Result(Status.NO_SUCH_MEME);
        }
        votes.purgeMeme(memeId);
        contentIndex.remove(memeId);
        tags.removeMeme(memeId);
        memes.deleteById(memeId);
        memeEvents.memeDeleted(memeId);
        return new Result(Status.DELETED);
    }
}
