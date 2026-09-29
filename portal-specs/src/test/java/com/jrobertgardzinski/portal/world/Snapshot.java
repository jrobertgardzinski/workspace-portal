package com.jrobertgardzinski.portal.world;

/**
 * A world as it was, and the one way back to it.
 *
 * <p>It exists for {@link UnitsOfWork}: a unit of work that fails has to leave the portal holding
 * exactly what it held before the step began, and none of the three fakes could say that until
 * now. Every fake hands back one of these instead of exposing its rows to be written over, so the
 * way back goes through the same doors the use cases write through — {@code store} for a mark,
 * {@code add} and {@code remove} for a saved reference — and a restore cannot put the world into a
 * state a use case could not have put it into.
 *
 * <p>Restoring is not a general undo. It is the state at ONE instant, put back, and it is only
 * sound because nothing in this process runs beside a unit of work: a step runs to completion
 * before the next one starts, so "as it was when this began" and "as it would be had this never
 * run" are the same world. A runner with two threads in it would need a write journal per
 * transaction, and would be a different kind of test.
 */
@FunctionalInterface
public interface Snapshot {

    /** Puts every row, every mark and every ballot back the way it was. */
    void restore();

    /** All of them, in the order given — the world is the sum of its parts. */
    static Snapshot of(Snapshot... parts) {
        Snapshot[] all = parts.clone();
        return () -> {
            for (Snapshot part : all) {
                part.restore();
            }
        };
    }
}
