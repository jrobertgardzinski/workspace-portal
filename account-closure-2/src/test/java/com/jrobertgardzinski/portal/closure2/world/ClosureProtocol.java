package com.jrobertgardzinski.portal.closure2.world;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The closure as a state machine: five states, three reports a part can make, and a table of which
 * report each state accepts. The table is drawn in {@code closure-protocol.adoc} at the module's root,
 * and {@code ClosureProtocolTest} reads the drawing and holds this class to it.
 * It is the protocol, not the transport: a monolith keeps it inside one transaction and separate
 * services keep it with a saga, and this class says nothing about which.
 *
 * <p>It holds the ORDER the promises rest on. Nothing is destroyed until the account is gone,
 * because {@link Report#DESTROYED} is accepted in {@link State#DESTROYING} only, and nothing but the
 * account going leads there. If the account cannot go, everything comes back, because the same
 * moment lands in {@link State#RESTORING} instead. A report out of its state is refused and changes
 * nothing; the same report twice is nothing new.
 */
public final class ClosureProtocol {

    public enum State {
        /** The account is locked; the parts hide their shares. The third report decides the rest. */
        HIDING,
        /** The account is gone. The parts destroy what they hid; nothing comes back, ever. */
        DESTROYING,
        /** The account stays, unlocked. The parts that hid bring it back; nothing may be destroyed. */
        RESTORING,
        /** Every part has destroyed its share. */
        DONE,
        /** Every part that hid has brought its share back. Alice is as she was. */
        WITHDRAWN
    }

    public enum Report { HIDDEN, DESTROYED, RESTORED }

    public enum Outcome { ACCEPTED, NOTHING_NEW, REFUSED }

    /** The account row, as far as the protocol is concerned: it goes, or it refuses and is unlocked. */
    public interface Account {
        /** @throws RuntimeException when the row refuses to go */
        void go();

        void unlock();
    }

    public static final Set<String> PARTS = Set.of("memes", "comments", "collections");

    /** The table. Giving up is accepted in HIDING, is nothing new in RESTORING, refused elsewhere. */
    private static final Map<State, Set<Report>> ACCEPTED_IN = Map.of(
            State.HIDING, EnumSet.of(Report.HIDDEN),
            State.DESTROYING, EnumSet.of(Report.DESTROYED),
            State.RESTORING, EnumSet.of(Report.RESTORED),
            State.DONE, EnumSet.noneOf(Report.class),
            State.WITHDRAWN, EnumSet.noneOf(Report.class));

    private final Account account;
    private final Set<String> hidden = new LinkedHashSet<>();
    private final Set<String> destroyed = new LinkedHashSet<>();
    private final Set<String> restored = new LinkedHashSet<>();
    private State state = State.HIDING;

    public ClosureProtocol(Account account) {
        this.account = account;
    }

    public State state() {
        return state;
    }

    /** Whether this part has reported its share hidden; only such a part has anything to restore. */
    public boolean hasHidden(String part) {
        return hidden.contains(part);
    }

    public Outcome report(Report report, String part) {
        if (!PARTS.contains(part)) {
            throw new IllegalArgumentException("no such part of the portal: " + part);
        }
        if (!ACCEPTED_IN.get(state).contains(report)) {
            return Outcome.REFUSED;
        }
        return switch (report) {
            case HIDDEN -> hidden(part);
            case DESTROYED -> destroyed(part);
            case RESTORED -> restored(part);
        };
    }

    /** Whoever has not reported never will: the account stays, and what was hidden comes back. */
    public Outcome givenUp() {
        return switch (state) {
            case HIDING -> {
                turnBack();
                yield Outcome.ACCEPTED;
            }
            case RESTORING -> Outcome.NOTHING_NEW;
            case DESTROYING, DONE, WITHDRAWN -> Outcome.REFUSED;
        };
    }

    private Outcome hidden(String part) {
        if (!hidden.add(part)) {
            return Outcome.NOTHING_NEW;
        }
        if (hidden.containsAll(PARTS)) {
            try {
                account.go();
                state = State.DESTROYING;
            } catch (RuntimeException refused) {
                turnBack();
            }
        }
        return Outcome.ACCEPTED;
    }

    private Outcome destroyed(String part) {
        if (!destroyed.add(part)) {
            return Outcome.NOTHING_NEW;
        }
        if (destroyed.containsAll(PARTS)) {
            state = State.DONE;
        }
        return Outcome.ACCEPTED;
    }

    private Outcome restored(String part) {
        if (!hidden.contains(part)) {
            return Outcome.REFUSED;
        }
        if (!restored.add(part)) {
            return Outcome.NOTHING_NEW;
        }
        if (restored.containsAll(hidden)) {
            state = State.WITHDRAWN;
        }
        return Outcome.ACCEPTED;
    }

    private void turnBack() {
        account.unlock();
        state = hidden.isEmpty() ? State.WITHDRAWN : State.RESTORING;
    }
}
