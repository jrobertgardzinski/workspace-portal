package com.jrobertgardzinski.portal.races;

import java.util.List;

/**
 * Who decides which of the records a broker could hand over next actually goes next.
 *
 * <p>There is exactly one such decision in the portal's specs today and nobody makes it: the
 * {@code while} loop takes whatever the list has in front. {@link #FIFO} is that decision, named.
 * Naming it is the whole of stage one — no scenario changes, no assertion is added, and from here
 * on a different answer is a thing that can be asked for.
 */
@FunctionalInterface
public interface Scheduler {

    Wire.Step next(List<Wire.Step> ready);

    /**
     * Oldest produced goes first — the loop that was there before, and the ONLY schedule under
     * which the sixty-six existing specs have ever run.
     *
     * <p>It reproduces the old behaviour exactly and not approximately. Draining a list in batches
     * while appending what each step announces IS a first-in-first-out queue, and the closure
     * bus's fixed {@code MEMES, COMMENTS, COLLECTIONS} fan-out becomes three steps enqueued in
     * that order, which this scheduler then takes in that order.
     */
    Scheduler FIFO = ready -> ready.get(0);
}
