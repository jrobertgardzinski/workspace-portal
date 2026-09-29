package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.unitofwork.UnitOfWork;

/**
 * The portal's transactions in this process — the first {@link UnitOfWork} here that can FAIL.
 *
 * <p>Until now this runner handed every participant {@code Runnable::run}: a unit of work that
 * always commits, which is why {@code AtomicClosureParticipant}'s whole reason for existing was
 * unstatable here. Its javadoc names the two failure modes it exists to prevent — "hidden rows with
 * no word owed" and "a word about rows that were never hidden" — and a transaction that cannot fail
 * can produce neither.
 *
 * <p>What a failure does: the rows go back to what they were when the step began ({@link Snapshot}),
 * and — once the outbox holds the announcements — nothing the step said leaves with it. What it
 * deliberately does NOT do is prove that Postgres would behave this way. It stages the shape of the
 * failure so the laws next door can be asked about it; the database's own promise is tested where
 * the database is, in the JDBC tests and in {@code e2e/}.
 *
 * <p>Nesting joins. A unit of work started inside one already running is the same transaction — one
 * snapshot, one ending — which is what a participant calling into a use case that opens its own
 * gets from a real transaction manager, and keeps a failure from being half-undone.
 */
public final class UnitsOfWork implements UnitOfWork {

    /** How a unit of work ends. */
    public enum Ending {

        /** It commits: every unit of work in this runner did this and only this until 29.09.2026. */
        COMMITS,

        /**
         * It rolls back: the rows are as they were and the work is as if it never ran. A
         * participant dying in the middle of ERASE, a connection lost between the mark and the
         * commit — every one of them looks like this from outside the process.
         */
        ROLLS_BACK
    }

    private final Portal world;

    private Ending next = Ending.COMMITS;

    private int depth;

    private int ran;

    private int rolledBack;

    UnitsOfWork(Portal world) {
        this.world = world;
    }

    /**
     * The next unit of work to begin ends this way, and the one after it commits again. One ending
     * per instruction, because a runner that could leave the portal permanently broken would be
     * staging a broken portal rather than a failure.
     */
    public void theNextOne(Ending ending) {
        next = ending;
    }

    /** Whether a unit of work has begun since this was last asked to count from zero. */
    public int ran() {
        return ran;
    }

    /** How many have rolled back — what tells a staged failure from an option that did nothing. */
    public int rolledBack() {
        return rolledBack;
    }

    @Override
    public void run(Runnable step) {
        if (depth > 0) {
            step.run();   // it joins the transaction already running; see the class javadoc
            return;
        }
        Snapshot before = world.snapshot();
        Ending ending = next;
        next = Ending.COMMITS;
        ran++;
        depth++;
        try {
            step.run();
        } catch (RuntimeException failed) {
            rolledBack++;
            before.restore();
            throw failed;
        } finally {
            depth--;
        }
        if (ending == Ending.ROLLS_BACK) {
            rolledBack++;
            before.restore();
        }
    }
}
