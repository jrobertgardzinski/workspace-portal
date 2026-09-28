package com.jrobertgardzinski.deletion;

/**
 * What one hop did with one event. Two shapes, one per protocol, rather than one per service:
 * the closure protocol has three copies of its own {@code ClosureOutcome}, and the day that
 * vocabulary gains a case, two of the three are the ones that forget it.
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
