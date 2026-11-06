package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.identity.UserId;

import java.time.Instant;
import java.util.List;

/**
 * The saga's own view of the memes table, keyed by the author's identity: the two halves of one
 * author's memes, the one write the saga makes, and the backlog the reaper watches. Everything
 * else reads the {@code active_memes} view and never sees a marked row.
 */
public interface MemeErasure {

    List<MemeMetadata> activeOf(UserId author);

    List<MemeMetadata> pendingOf(UserId author);

    /** Writes the erasure state of one meme — status and instant — and nothing else. */
    void store(MemeMetadata state);

    /** Marked strictly before the cutoff, oldest first: the reaper's backlog. */
    List<MemeMetadata> pendingSince(Instant cutoff);
}
