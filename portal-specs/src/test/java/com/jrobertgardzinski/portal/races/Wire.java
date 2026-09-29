package com.jrobertgardzinski.portal.races;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the portal's two buses have instead of a broker — and the one place that decides what
 * ORDER means in this process.
 *
 * <p>It used to be a {@code List} per bus, drained to a fixed point by a {@code while} loop. That
 * loop is one schedule out of many, chosen by nobody: the list's order, and — inside the closure
 * bus — a hard-coded {@code List.of(MEMES, COMMENTS, COLLECTIONS)} for the three consumer groups
 * that production runs independently. Every promise in {@code ../specs} is proven under that one
 * schedule and no other.
 *
 * <p>This class keeps one queue per LANE — a (topic, partition key, consumer group) triple, which
 * is what a broker actually serialises. The head of every non-empty lane is a step that could
 * happen next; which one does is a {@link Scheduler}'s decision. {@link Scheduler#FIFO} reproduces
 * the old loop exactly, which is why the existing specs did not have to change a character.
 *
 * <p>Nothing here knows what a meme is. A step is a label and a {@link Runnable}; the buses build
 * them.
 */
public final class Wire {

    /**
     * What a broker keeps in order and what it does not. Records on one topic under one key reach
     * one consumer group in the order they were produced; anything else is free. A lane is that
     * triple, so "the head of every lane" is exactly the set of records a broker could hand over
     * next.
     */
    public record Lane(String topic, String key, String group) {

        @Override
        public String toString() {
            return topic + "[" + key + "]→" + group;
        }
    }

    /**
     * One record, handed to one consumer group, once. {@code seq} is the order it was produced in
     * — the only thing {@link Scheduler#FIFO} looks at. {@code label} identifies the step across
     * replays of the same seed, which is what lets the explorer re-walk a prefix and be sure it is
     * walking the same one.
     */
    public record Step(long seq, Lane lane, String label, Runnable action, boolean duplicate) {

        @Override
        public String toString() {
            return label + (duplicate ? " (again)" : "");
        }
    }

    private final Map<Lane, Deque<Step>> lanes = new LinkedHashMap<>();

    /**
     * Consumer groups that are not consuming. A part of the portal that is down does not DROP its
     * records — the broker keeps them and its offset does not move, so when it comes back it reads
     * everything it missed, in order, before it reads anything new.
     *
     * <p>This used to be a check inside the delivery, which threw the record away and left the
     * part with a gap it could never notice. The difference shows the moment a part comes back to
     * a mark it never heard and a compensation queued behind it: the two arrive in that order,
     * from one partition, and that is the only reason nothing stays reserved.
     */
    private final Set<String> held = new HashSet<>();

    private long minted;

    public void enqueue(Lane lane, String label, Runnable action) {
        lanes.computeIfAbsent(lane, unused -> new ArrayDeque<>())
                .addLast(new Step(minted++, lane, label, action, false));
    }

    /**
     * The same record handed over a second time — at-least-once, which the portal gets for free
     * and has to survive. It joins the TAIL of its own lane, because a broker that redelivers does
     * not thereby overtake anything already ahead of it on that partition.
     */
    public void redeliver(Step step) {
        lanes.computeIfAbsent(step.lane(), unused -> new ArrayDeque<>())
                .addLast(new Step(minted++, step.lane(), step.label(), step.action(), true));
    }

    /**
     * The record was handed over and its consumer did not commit — so the offset never moved, and
     * the broker will hand the SAME record over again before anything behind it on that partition.
     * It goes back to the HEAD of its lane, keeping the sequence it was produced with.
     *
     * <p>Not {@link #redeliver}, which is a broker repeating itself having gone wrong nowhere and
     * joins the tail. A consumer whose transaction rolled back has not consumed anything at all,
     * and modelling that as a lost record would be the same mistake as modelling a part that is
     * down by dropping its messages.
     */
    public void unconsumed(Step step) {
        lanes.computeIfAbsent(step.lane(), unused -> new ArrayDeque<>()).addFirst(step);
    }

    /** The heads of every matching non-empty lane, oldest first — everything that could happen next. */
    public List<Step> ready(Predicate<Lane> only) {
        List<Step> heads = new ArrayList<>();
        lanes.forEach((lane, queue) -> {
            if (!queue.isEmpty() && only.test(lane) && !held.contains(lane.group())) {
                heads.add(queue.peekFirst());
            }
        });
        heads.sort((left, right) -> Long.compare(left.seq(), right.seq()));
        return heads;
    }

    public boolean anything(Predicate<Lane> only) {
        return !ready(only).isEmpty();
    }

    /** Takes this step off the head of its lane and runs it. Whatever it announces joins the wire. */
    public void run(Step step) {
        Deque<Step> queue = lanes.get(step.lane());
        if (queue == null || queue.isEmpty() || queue.peekFirst().seq() != step.seq()) {
            throw new IllegalStateException("not at the head of its lane: " + step);
        }
        queue.removeFirst();
        step.action().run();
    }

    /**
     * Runs matching steps until none is left, one at a time, in the order the scheduler asks for.
     * With {@link Scheduler#FIFO} this is the old {@code while (!inFlight.isEmpty())} loop.
     */
    public void drain(Scheduler scheduler, Predicate<Lane> only) {
        List<Step> ready = ready(only);
        while (!ready.isEmpty()) {
            run(scheduler.next(ready));
            ready = ready(only);
        }
    }

    /** That consumer group stops consuming; its records wait for it where they are. */
    public void hold(String group) {
        held.add(group);
    }

    /** It comes back — and reads what it missed, in order, before it reads anything new. */
    public void release(String group) {
        held.remove(group);
    }

    /**
     * What is still on the wire, canonically — part of a state, and the explorer's memo key.
     *
     * <p>The partition KEY is left out and the label is not. A saga's id is minted at random on
     * every build, so a rendering that carried it would be different text for the same situation
     * and no two replays of a seed would ever agree; the label already names the meme a cascade
     * is about, which is the only key whose value distinguishes two lanes here.
     */
    public List<String> pending(Predicate<Lane> only) {
        List<String> waiting = new ArrayList<>();
        lanes.forEach((lane, queue) -> {
            if (only.test(lane)) {
                queue.forEach(step -> waiting.add(lane.topic() + "→" + lane.group()
                        + " " + step.label() + (step.duplicate() ? " (again)" : "")));
            }
        });
        return waiting.stream().sorted().toList();
    }
}
