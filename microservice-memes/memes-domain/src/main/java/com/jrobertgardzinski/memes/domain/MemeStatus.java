package com.jrobertgardzinski.memes.domain;

/**
 * Whether a meme is part of the gallery or is waiting to be erased. Two values, and the second one
 * is the whole reason this type exists: an account deletion is a SAGA, and a saga that has already
 * destroyed the content it was asked about has nothing left to compensate with. A meme therefore
 * leaves the public world before it leaves the disk, and the step that hides it is reversible.
 *
 * <p><strong>PENDING_ERASURE, not TO_DELETE.</strong> The vocabulary is the GDPR's: article 17 is
 * the right to ERASURE, and every artefact around this feature — the saga's commands, the reaper's
 * query, the ADR — says erasure for the same reason the purge rules say "anonymise" rather than
 * "scrub". A name that matches the regulation is a name a lawyer, an auditor and a reviewer read
 * the same way; "to delete" is a programmer's to-do list.
 *
 * <p><strong>Why a status and not a flag.</strong> A boolean answers one question and grows a
 * second column the day a third state appears (an erasure held for a legal dispute, say); a status
 * answers "what IS this row" and makes the illegal combinations unrepresentable — see the
 * invariant in {@link MemeMetadata}, which is mirrored by a CHECK constraint in the schema.
 *
 * @see MemeMetadata#markForErasure(java.time.Instant)
 * @see MemeMetadata#restore()
 */
public enum MemeStatus {

    /** In the gallery: the only status any public read may ever return. */
    ACTIVE,

    /**
     * Marked by a running account-deletion saga: invisible to every public read, still on disk,
     * still restorable by the saga's compensation. Nothing but the saga's own closure command may
     * turn this into an actual deletion — never the mere passage of time.
     */
    PENDING_ERASURE
}
