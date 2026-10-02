package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.memes.domain.MemeEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the memes part announced, written down — this suite's own wiring of {@link MemeEvents},
 * not a fake of the port: it exists so a step can say "memes announced" and a later step can say
 * "<part> has heard", and so the two are visibly different moments.
 *
 * <p>Each listener hears each announcement once ({@link #newFor}), unless a scenario delivers it
 * again on purpose ({@link #againFor}). How the announcement travels is not said.
 */
public final class MemeAnnouncements implements MemeEvents {

    private final List<String> announced = new ArrayList<>();
    private final Map<String, Integer> heardUpTo = new HashMap<>();

    @Override
    public void memeDeleted(String memeId) {
        announced.add(memeId);
    }

    /** Every meme announced gone so far, in order. */
    public List<String> announced() {
        return List.copyOf(announced);
    }

    /** The announcements this listener has not heard yet; from now on it has. */
    public List<String> newFor(String listener) {
        int from = heardUpTo.getOrDefault(listener, 0);
        heardUpTo.put(listener, announced.size());
        return List.copyOf(announced.subList(from, announced.size()));
    }

    /** Everything announced so far, delivered to this listener once more. */
    public List<String> againFor(String listener) {
        heardUpTo.put(listener, announced.size());
        return announced();
    }
}
