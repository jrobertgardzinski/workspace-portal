package com.jrobertgardzinski.memes.domain;

import com.jrobertgardzinski.identity.UserId;

import java.time.Instant;
import java.util.Optional;

/**
 * A {@link Meme} without its image: the identifier, the id of whoever uploaded it, the format its
 * bytes are stored in, and — since the offboarding saga learnt to compensate — whether the row is part of the
 * gallery at all. This is what the questions ABOUT a meme need: "is there such a meme?", "who may
 * delete it?", "whose tags are these?", "is this waiting to be erased?" — and none of them needs
 * the picture.
 *
 * <p>The type exists because {@link Meme} made the bytes unavoidable: every authorisation check
 * dragged the full image out of object storage just to read one field off it, and a meme whose
 * bytes were missing from the active store answered "no such meme" to its own author. A row
 * and its picture are two different facts; this record is the first one alone.
 *
 * <p><strong>This is where the erasure state lives, and deliberately not on {@link Meme}.</strong>
 * Marking a leaver's memes is a bulk operation over rows — hundreds of them, in one transaction —
 * and every one of those rows carries a photograph in object storage. Putting the transition on the
 * bytes-carrying record would mean a blob read per meme to change one enum, which is the exact cost
 * this type was extracted to stop paying. Nothing is lost by it: the picture is only ever loaded
 * for something that is {@link MemeStatus#ACTIVE}, because every public read goes through a
 * repository that cannot see anything else.
 *
 * <p><strong>Transitions are methods, never a setter.</strong> {@link #markForErasure(Instant)} and
 * {@link #restore()} are the only two ways the status ever changes, they return a new value (this
 * is a record — the old state stays valid), and both are IDEMPOTENT: Kafka delivers at-least-once,
 * so each of them is going to be asked to do what it has already done. Marking twice keeps the
 * FIRST timestamp, because the mark's age is what the reaper's query and the stuck-erasure alarm
 * are measured against, and a redelivered command must not make an old obligation look fresh.
 */
public record MemeMetadata(String id, Optional<UserId> authorId, String format,
                           MemeStatus status, Instant markedForErasureAt) {

    /**
     * The invariant, in the one place that can enforce it: a mark and its timestamp exist together
     * or not at all. The schema repeats it as a CHECK constraint — a row can also be written by a
     * migration or by a human at a psql prompt, and neither goes through this constructor.
     */
    public MemeMetadata {
        if ((status == MemeStatus.PENDING_ERASURE) != (markedForErasureAt != null)) {
            throw new IllegalArgumentException(
                    "a meme is PENDING_ERASURE exactly when it carries the instant it was marked;"
                            + " got status=" + status + ", markedForErasureAt=" + markedForErasureAt);
        }
    }

    /**
     * A meme in the gallery — the shorthand for every caller that has nothing to do with erasure,
     * which is nearly all of them (an upload, a listing, an authorisation check).
     */
    public MemeMetadata(String id, UserId author, String format) {
        this(id, Optional.of(author), format, MemeStatus.ACTIVE, null);
    }

    /** An anonymised row: the account is gone, the meme stayed, and it belongs to nobody. */
    public MemeMetadata(String id, String format, MemeStatus status, Instant markedForErasureAt) {
        this(id, Optional.empty(), format, status, markedForErasureAt);
    }

    /**
     * The reversible half of an account deletion: the meme leaves every public read and stays on
     * disk. Idempotent — a redelivered command finds the row already marked and keeps the original
     * instant, so re-commanding never resets the clock the erasure backlog is watched by.
     */
    public MemeMetadata markForErasure(Instant at) {
        return status == MemeStatus.PENDING_ERASURE
                ? this
                : new MemeMetadata(id, authorId, format, MemeStatus.PENDING_ERASURE, at);
    }

    /**
     * The compensation: back into the gallery, exactly as before the mark. Idempotent for the same
     * reason as its opposite — and a no-op on an ACTIVE meme rather than an error, because the
     * orchestrator compensating a saga cannot know which participants got as far as marking.
     */
    public MemeMetadata restore() {
        return status == MemeStatus.ACTIVE
                ? this
                : new MemeMetadata(id, authorId, format, MemeStatus.ACTIVE, null);
    }

    /** Whether a running saga has this meme reserved for erasure. */
    public boolean isPendingErasure() {
        return status == MemeStatus.PENDING_ERASURE;
    }

    /** Whether this caller is the uploader. An anonymised row (no id) is nobody's. */
    public boolean isOwnedBy(UserId caller) {
        return authorId.map(caller::equals).orElse(false);
    }
}
