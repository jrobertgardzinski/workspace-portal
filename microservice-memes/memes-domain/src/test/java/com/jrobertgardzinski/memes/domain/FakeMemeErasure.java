package com.jrobertgardzinski.memes.domain;

import java.util.Optional;
import com.jrobertgardzinski.identity.UserId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link MemeErasure} over the same meme map the repository fakes use, so a test can
 * run the saga's two phases end to end: mark, then erase — or mark, then restore.
 *
 * <p>The marks live in their own map rather than on the {@link Meme} records, for the same reason
 * the real schema keeps them on the row: the meme's bytes have nothing to do with its erasure
 * state. Whatever is not marked is ACTIVE, which is exactly what the {@code active_memes} view says.
 *
 * <p>Public, and this module's own test-jar publishes it: it is the one reference stand-in for
 * {@link MemeErasure}, right beside the port and {@link MemeErasureContractTest}, so a consumer
 * that needs only the erasure axis (not a full {@link MemeRepository}) never has a reason to write
 * its own.
 */
public class FakeMemeErasure implements MemeErasure {

    private final Map<String, Meme> memes;
    private final Map<String, Instant> marks = new HashMap<>();

    public FakeMemeErasure(Map<String, Meme> memes) {
        this.memes = memes;
    }



    @Override
    public List<MemeMetadata> activeOf(UserId author) {
        return byAuthorId(author, false);
    }

    @Override
    public List<MemeMetadata> pendingOf(UserId author) {
        return byAuthorId(author, true);
    }

    private List<MemeMetadata> byAuthorId(UserId author, boolean marked) {
        List<MemeMetadata> found = new ArrayList<>();
        for (Meme meme : memes.values()) {
            if (meme.authorId().equals(Optional.of(author)) && marks.containsKey(meme.id()) == marked) {
                found.add(metadataOf(meme));
            }
        }
        return found;
    }


    @Override
    public void store(MemeMetadata state) {
        if (state.isPendingErasure()) {
            marks.put(state.id(), state.markedForErasureAt());
        } else {
            marks.remove(state.id());
        }
    }

    @Override
    public List<MemeMetadata> pendingSince(Instant cutoff) {
        return memes.values().stream()
                .filter(meme -> marks.containsKey(meme.id()))
                .filter(meme -> marks.get(meme.id()).isBefore(cutoff))
                .map(this::metadataOf)
                .toList();
    }

    /** Whether this meme is hidden from the gallery right now — what a read-side assertion asks. */
    public boolean isMarked(String memeId) {
        return marks.containsKey(memeId);
    }

    /**
     * The reservation goes with the row it was made against — for whoever destroys that row
     * ({@link FakeMemeRepository#deleteById}).
     *
     * <p>Not a convenience: in the schema the status IS a column of the memes row, so a deleted
     * meme cannot leave a mark behind, and this class keeps the marks in a map of their own purely
     * because the bytes have nothing to do with the erasure state. Without this call the two part
     * company the moment anything deletes — the map still answers {@link #isMarked} for a meme
     * nothing holds any more, which is a state the adapter cannot reach and no caller should have
     * to know about.
     */
    protected void forgetMark(String memeId) {
        marks.remove(memeId);
    }

    /** The reservations, for a test that fingerprints the whole world (idempotence). */
    public Map<String, Instant> marks() {
        return Map.copyOf(marks);
    }

    /** The port's view of a stored meme, reservation included — what the repository axis
     *  built on top of this class ({@link FakeMemeRepository}) answers reads with. */
    protected MemeMetadata metadataOf(Meme meme) {
        Instant marked = marks.get(meme.id());
        return new MemeMetadata(meme.id(), meme.authorId(), meme.format(),
                marked == null ? MemeStatus.ACTIVE : MemeStatus.PENDING_ERASURE, marked);
    }
}
