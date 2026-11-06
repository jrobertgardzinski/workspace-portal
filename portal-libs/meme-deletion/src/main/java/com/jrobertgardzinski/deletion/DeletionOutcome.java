package com.jrobertgardzinski.deletion;

/**
 * What one hop did with one event. Two shapes, one per protocol, rather than one per service —
 * because the day a vocabulary gains a case, the copies that forget it are the ones nobody was
 * looking at. The closure protocol had three copies of its own {@code ClosureOutcome} until
 * 28.09.2026 and now has one, in {@code account-closure}, for this reason.
 *
 * <p>There is no failure case on purpose. A store that failed throws, and the adapter's retry —
 * which is transport, and differs per service — is what decides how long to keep trying.
 */
public sealed interface DeletionOutcome {

    /** The hop dropped something: this many rows. */
    record Dropped(int rows) implements DeletionOutcome {
    }

    /**
     * The hop had nothing to drop. A redelivered event, a meme this hop never held anything of,
     * an event that names nothing, or one that is not this hop's business at all — the cascade
     * treats all four the same, and that is what makes it idempotent.
     */
    record Nothing() implements DeletionOutcome {
    }
}
