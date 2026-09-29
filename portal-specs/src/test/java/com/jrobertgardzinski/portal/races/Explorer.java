package com.jrobertgardzinski.portal.races;

import com.jrobertgardzinski.portal.closure.ClosureInOneProcess;
import com.jrobertgardzinski.portal.world.UnitsOfWork;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs one {@link Seed} once per legal schedule and reports what came of each.
 *
 * <p>It walks the tree of decisions depth first. A schedule is a list of choices, and a node is
 * reached by building the seed again and replaying its choices — which costs more than snapshotting
 * a world would, and buys not having to write a snapshot of one. A whole account closure takes
 * milliseconds, so the arithmetic is on the side of replaying.
 *
 * <p><strong>What makes this finite.</strong> Two nodes with the same fingerprint, the same wire in
 * front of them and the same budgets left have the same future, so the second one is not walked.
 * The pruning is why the set of END STATES this reports is exact — re-reaching a state can add no
 * outcome that was not already found — while the count of schedules is only a count of the ones
 * actually walked.
 *
 * <p><strong>What it is allowed to do.</strong> Only what a broker could: it takes the head of a
 * lane, never the middle of one, so records on one topic under one key reach one consumer group in
 * the order they were produced. Everything else is free, and the freest thing of all is the seam —
 * the saga is keyed by the leaver, the cascade by the meme, and nothing on earth orders those two
 * against each other.
 */
public final class Explorer {

    /**
     * How far to look. {@code steps} truncates a single schedule, {@code schedules} the whole
     * search; either one being hit is reported, because a search that stopped early proves
     * nothing about what it did not reach.
     */
    public record Bounds(int steps, int duplicates, int failures, int schedules) {

        public static Bounds ofDefault() {
            return new Bounds(40, 0, 0, 20_000);
        }

        public Bounds withDuplicates(int duplicates) {
            return new Bounds(steps, duplicates, failures, schedules);
        }

        /** How many units of work may fail in one schedule. Each one costs a great deal of search. */
        public Bounds withFailures(int failures) {
            return new Bounds(steps, duplicates, failures, schedules);
        }
    }

    /** A law that did not hold, and the shortest schedule found that breaks it. */
    public record Violation(String law, String sentence, List<String> schedule) {
    }

    /** One distinct end state, and one schedule that reaches it. */
    public record Outcome(String state, List<String> example, int reached) {
    }

    public record Report(String seed, String about, int schedules, int statesVisited, int pruned,
                         boolean complete, List<Outcome> outcomes, List<Violation> violations,
                         Names names) {

        /**
         * What goes into the repository and gets diffed: the distinct end states of this seed, and
         * whether every law held. No counts and no example schedules — those change whenever the
         * search does, and a file that churns on every refactor is a file nobody reads.
         *
         * <p>A diff here is a DECISION. A new end state means this seed can now finish in a way it
         * could not before, and somebody has to write the sentence saying whether that is all
         * right.
         */
        public String approved() {
            StringBuilder text = new StringBuilder();
            text.append("# ").append(seed).append('\n').append('\n');
            text.append(wrapped(about)).append('\n');
            text.append("search: ").append(complete
                    ? "exhaustive — every schedule this transport allows was walked"
                    : "TRUNCATED — the bounds were hit, so this list may be incomplete").append('\n');
            text.append("distinct end states: ").append(outcomes.size()).append('\n');
            char label = 'A';
            for (Outcome outcome : outcomes) {
                text.append('\n').append('[').append(label++).append("]\n");
                names.read(outcome.state()).lines()
                        .forEach(line -> text.append("    ").append(line).append('\n'));
            }
            text.append('\n');
            if (violations.isEmpty()) {
                text.append("every law held on every schedule walked\n");
            } else {
                text.append("LAWS BROKEN\n");
                violations.forEach(broken -> text.append("  ! ").append(broken.law())
                        .append("\n    ").append(names.read(broken.sentence())).append('\n'));
            }
            return text.toString();
        }

        /** The working copy: counts, and one schedule that reaches each end state. */
        public String detail() {
            StringBuilder text = new StringBuilder(approved());
            text.append("\n---\n\nschedules walked: ").append(schedules)
                    .append("   nodes visited: ").append(statesVisited)
                    .append("   pruned as already seen: ").append(pruned).append("\n");
            char label = 'A';
            for (Outcome outcome : outcomes) {
                text.append("\n[").append(label++).append("] reached by ").append(outcome.reached())
                        .append(" of the schedules walked; one way there:\n");
                outcome.example().forEach(step ->
                        text.append("      · ").append(names.read(step)).append('\n'));
            }
            violations.forEach(broken -> {
                text.append("\n! ").append(broken.law()).append(" — after:\n");
                broken.schedule().forEach(step ->
                        text.append("      · ").append(names.read(step)).append('\n'));
            });
            return text.toString();
        }

        private static String wrapped(String about) {
            StringBuilder lines = new StringBuilder();
            int room = 0;
            for (String word : about.split(" ")) {
                if (room + word.length() > 92) {
                    lines.append('\n');
                    room = 0;
                }
                lines.append(word).append(' ');
                room += word.length() + 1;
            }
            return lines.append('\n').toString();
        }
    }

    public static Report explore(Seed seed, Bounds bounds) {
        Deque<List<Integer>> frontier = new ArrayDeque<>();
        frontier.push(List.of());
        Set<String> seen = new HashSet<>();
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        Map<String, Violation> violations = new LinkedHashMap<>();
        int walked = 0;
        int pruned = 0;
        int visited = 0;
        boolean complete = true;

        while (!frontier.isEmpty()) {
            if (walked >= bounds.schedules()) {
                complete = false;
                break;
            }
            Walk walk = replay(seed, frontier.pop(), bounds);
            visited++;
            walk.violations.forEach(broken -> violations.putIfAbsent(broken.law(), broken));
            boolean truncated = walk.trace.size() >= bounds.steps();
            if (walk.options.isEmpty() || truncated) {
                // a finished schedule is always counted; memoisation prunes FUTURES, and an end
                // state has none, so pruning one would only hide how many ways there are to it
                complete &= !truncated;
                walked++;
                String state = walk.state();
                Outcome already = outcomes.get(state);
                outcomes.put(state, already == null
                        ? new Outcome(state, List.copyOf(walk.trace), 1)
                        : new Outcome(state, already.example(), already.reached() + 1));
                continue;
            }
            if (!seen.add(walk.memo())) {
                pruned++;
                continue;
            }
            for (int choice = walk.options.size() - 1; choice >= 0; choice--) {
                List<Integer> longer = new ArrayList<>(walk.path);
                longer.add(choice);
                frontier.push(List.copyOf(longer));
            }
        }
        return new Report(seed.name(), seed.about(), walked, visited, pruned, complete,
                List.copyOf(outcomes.values()), List.copyOf(violations.values()), seed.names());
    }

    // ---- one schedule, replayed from the beginning -------------------------------------------

    private record Option(String label, Runnable go) {
    }

    private static final class Walk {
        ClosureInOneProcess portal;
        Seed.Memory memory;
        List<Integer> path;
        final List<String> trace = new ArrayList<>();
        final List<Violation> violations = new ArrayList<>();
        List<Option> options = List.of();

        String state() {
            String rows = portal.world().fingerprint();
            List<String> lines = new ArrayList<>();
            lines.add("identity was told: " + portal.verdicts());
            lines.add("the parts confirmed: " + new java.util.TreeMap<>(portal.confirmations()));
            lines.add("still on the wire: " + portal.wire().pending(lane -> true));
            // only when there is something in it: an outbox nobody has left anything in is not a
            // fact about a schedule, and a line saying so in every file would say nothing
            List<String> unsent = portal.world().unitsOfWork().unsent();
            if (!unsent.isEmpty()) {
                lines.add("held in the outbox: " + unsent);
            }
            lines.add("rows:" + (rows.isEmpty() ? " the portal holds nothing at all"
                    : "\n  " + rows.replace("\n", "\n  ")));
            return String.join("\n", lines);
        }

        String memo() {
            return state() + "|sweeps=" + sweepsLeft + "|duplicates=" + duplicatesLeft
                    + "|failures=" + failuresLeft;
        }

        int sweepsLeft;
        int duplicatesLeft;
        int failuresLeft;
    }

    private static Walk replay(Seed seed, List<Integer> path, Bounds bounds) {
        Seed.Started started = seed.start();
        Walk walk = new Walk();
        walk.portal = started.portal();
        walk.memory = started.memory();
        walk.path = path;
        walk.sweepsLeft = seed.sweeps();
        walk.duplicatesLeft = bounds.duplicates();
        walk.failuresLeft = bounds.failures();

        // what a part says it reserved, held against what it is holding, at the instant it says it
        walk.portal.watch((participant, leaver, reserved) -> {
            int actually = leaver == null ? 0 : walk.portal.reservedOn(participant, leaver);
            if (reserved > actually) {
                walk.violations.add(new Violation(
                        "a part never confirms more than it is holding",
                        participant + " confirmed " + reserved + " reserved while holding "
                                + actually,
                        List.copyOf(walk.trace)));
            }
        });

        for (int choice : path) {
            List<Option> options = optionsOf(walk);
            if (options.isEmpty()) {
                break;
            }
            take(walk, options.get(Math.min(choice, options.size() - 1)));
        }
        walk.options = optionsOf(walk);
        boolean truncated = walk.trace.size() >= bounds.steps();
        if (truncated) {
            walk.options = List.of();
        }
        // a schedule cut off by the bounds is NOT a quiet one, and the laws that may only be asked
        // in silence must not be asked here. Asking them anyway made a truncated search report
        // four broken laws that were nothing but the middle of a run: a reservation not yet
        // released, a thread whose cascade had not been delivered, a pointer whose hop was still
        // on the wire. Every one of them is what being mid-flight LOOKS like
        checkAll(walk, walk.options.isEmpty() && !truncated);
        return walk;
    }

    private static void take(Walk walk, Option option) {
        walk.trace.add(option.label());
        option.go().run();
        checkAll(walk, false);
    }

    private static void checkAll(Walk walk, boolean quiet) {
        for (Invariant law : Invariant.ALL) {
            if (law.onlyInSilence() && !quiet) {
                continue;
            }
            Optional<String> broken = law.broken(walk.portal, walk.memory);
            broken.ifPresent(sentence -> walk.violations.add(
                    new Violation(law.name(), sentence, List.copyOf(walk.trace))));
        }
    }

    /**
     * Everything the transport could do next: the head of every lane, each optionally handed over
     * twice or handed to a consumer whose transaction does not commit, the outbox relay while
     * anything is waiting in it, and — while the clock still has a timeout left to reach — the
     * sweeper.
     */
    private static List<Option> optionsOf(Walk walk) {
        List<Option> options = new ArrayList<>();
        for (Wire.Step step : walk.portal.wire().ready(lane -> true)) {
            options.add(new Option(step.lane() + " " + step,
                    () -> walk.portal.wire().run(step)));
            if (walk.duplicatesLeft > 0) {
                options.add(new Option(step.lane() + " " + step + " [and again]", () -> {
                    walk.duplicatesLeft--;
                    walk.portal.wire().run(step);
                    walk.portal.wire().redeliver(step);
                }));
            }
            if (walk.failuresLeft > 0 && ClosureInOneProcess.TRANSACTIONAL.test(step.lane())) {
                options.add(new Option(step.lane() + " " + step + " [its transaction rolls back]",
                        () -> {
                            walk.failuresLeft--;
                            UnitsOfWork transactions = walk.portal.world().unitsOfWork();
                            int failedBefore = transactions.rolledBack();
                            transactions.theNextOne(UnitsOfWork.Ending.ROLLS_BACK);
                            walk.portal.wire().run(step);
                            transactions.theNextOne(UnitsOfWork.Ending.COMMITS);
                            if (transactions.rolledBack() > failedBefore) {
                                // its offset never moved, so the broker still owes it to them
                                walk.portal.wire().unconsumed(step);
                            }
                        }));
                // an outbox row waits for a relay; a record sent straight to the broker is simply
                // gone, and what re-issues it is the next sweep
                boolean hasAnOutbox = ClosureInOneProcess.THROUGH_AN_OUTBOX.test(step.lane());
                options.add(new Option(step.lane() + " " + step
                        + (hasAnOutbox ? " [it commits and the process dies unsent]"
                        : " [it commits and what it said is lost]"),
                        () -> {
                            walk.failuresLeft--;
                            UnitsOfWork transactions = walk.portal.world().unitsOfWork();
                            transactions.theNextOne(hasAnOutbox
                                    ? UnitsOfWork.Ending.COMMITS_AND_SAYS_NOTHING_YET
                                    : UnitsOfWork.Ending.COMMITS_AND_LOSES_WHAT_IT_SAID);
                            walk.portal.wire().run(step);
                            transactions.theNextOne(UnitsOfWork.Ending.COMMITS);
                        }));
            }
        }
        if (!walk.portal.world().unitsOfWork().unsent().isEmpty()) {
            // always available, never compulsory: the layer assumes the relay eventually runs and
            // asks whether the portal survives every delay of it, which is what an outbox promises
            options.add(new Option("the outbox relay sends "
                    + walk.portal.world().unitsOfWork().unsent(),
                    () -> walk.portal.world().unitsOfWork().relay()));
        }
        if (walk.sweepsLeft > 0) {
            options.add(new Option("the clock reaches the purge timeout", () -> {
                walk.sweepsLeft--;
                walk.portal.sweep();
            }));
            if (walk.failuresLeft > 0) {
                // the sweep is not a record, so there is no offset to leave alone: a sweeper whose
                // transaction did not commit has selected nothing and charged nothing, and the next
                // tick finds the same cases overdue. The clock still moved
                options.add(new Option(
                        "the clock reaches the purge timeout [the sweep's transaction rolls back]",
                        () -> {
                            walk.sweepsLeft--;
                            walk.failuresLeft--;
                            UnitsOfWork transactions = walk.portal.world().unitsOfWork();
                            transactions.theNextOne(UnitsOfWork.Ending.ROLLS_BACK);
                            walk.portal.sweep();
                            transactions.theNextOne(UnitsOfWork.Ending.COMMITS);
                        }));
                options.add(new Option(
                        "the clock reaches the purge timeout [it commits and what it said is lost]",
                        () -> {
                            walk.sweepsLeft--;
                            walk.failuresLeft--;
                            UnitsOfWork transactions = walk.portal.world().unitsOfWork();
                            transactions.theNextOne(
                                    UnitsOfWork.Ending.COMMITS_AND_LOSES_WHAT_IT_SAID);
                            walk.portal.sweep();
                            transactions.theNextOne(UnitsOfWork.Ending.COMMITS);
                        }));
            }
        }
        return options;
    }

    private Explorer() {
    }
}
