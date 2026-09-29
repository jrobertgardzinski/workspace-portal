package com.jrobertgardzinski.portal.world;

import com.jrobertgardzinski.unitofwork.UnitOfWork;

import java.util.ArrayList;
import java.util.List;

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
        ROLLS_BACK,

        /**
         * It commits, and the process dies before its records leave the outbox. The rows are
         * written and nothing has been said yet; whoever restarts finds the outbox rows and sends
         * them ({@link #relay()}), which is the half of the pattern the table exists for.
         */
        COMMITS_AND_SAYS_NOTHING_YET,

        /**
         * The word goes out and the work does not — the ONE failure the outbox makes impossible,
         * kept here so a test can ask whether the laws next door would see it. Nothing offers this
         * to a search: a layer that stages a failure its own design prevents and then reports it is
         * a layer that measures its own staging.
         */
        SENDS_AND_THEN_ROLLS_BACK
    }

    private final Portal world;

    private Ending next = Ending.COMMITS;

    private int depth;

    private int ran;

    private int rolledBack;

    /** What the unit of work now running has said and not sent yet — its outbox rows. */
    private final List<Record> pending = new ArrayList<>();

    /** What a dead process left in the outbox: committed, unsent, and somebody else's to send. */
    private final List<Record> stranded = new ArrayList<>();

    /** One outbox row: what it says, and the sending of it. */
    private record Record(String what, Runnable send) {
    }

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

    /**
     * A record produced INSIDE a unit of work leaves with it, or not at all. That is the whole of
     * the transactional outbox, and the reason the deployed stack has one: a confirmation written
     * to a table in the same transaction as the mark cannot be sent by a transaction that never
     * committed, and cannot be lost by one that did.
     *
     * <p>Outside a unit of work it goes out at once, because nothing is holding it — a verdict the
     * orchestrator publishes, an announcement an author's own teardown makes.
     */
    public void onCommit(String what, Runnable send) {
        if (depth == 0) {
            send.run();
            return;
        }
        pending.add(new Record(what, send));
    }

    /** What the outbox is holding, committed and unsent — part of the portal's state, like a table. */
    public List<String> unsent() {
        return stranded.stream().map(Record::what).sorted().toList();
    }

    /**
     * The relay comes round and sends what a dead process left behind, oldest first. In the
     * deployed stack this is the outbox poller on the next tick, or the next start-up.
     */
    public void relay() {
        List<Record> sending = List.copyOf(stranded);
        stranded.clear();
        sending.forEach(record -> record.send().run());
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
            pending.clear();
            depth--;
            before.restore();
            throw failed;
        }
        depth--;
        List<Record> said = List.copyOf(pending);
        pending.clear();
        switch (ending) {
            case ROLLS_BACK -> {
                rolledBack++;
                before.restore();
                // and nothing it said goes anywhere: the two halves fail together
            }
            case COMMITS_AND_SAYS_NOTHING_YET -> stranded.addAll(said);
            case SENDS_AND_THEN_ROLLS_BACK -> {
                rolledBack++;
                said.forEach(record -> record.send().run());
                before.restore();
            }
            case COMMITS -> said.forEach(record -> record.send().run());
        }
    }
}
