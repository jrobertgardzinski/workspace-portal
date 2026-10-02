package com.jrobertgardzinski.portal.closure2.world;

import com.jrobertgardzinski.comments.domain.CommentEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the comments part announced, written down — this suite's own wiring of
 * {@link CommentEvents}, for the reason {@link MemeAnnouncements} gives.
 *
 * <p>Note who calls it: not the use cases. {@code PurgeUserComments} and {@code DeleteThread}
 * return the ids they dropped and announce nothing, because in the product the announcement has
 * to share the delete's transaction and that is the caller's; so here the caller is
 * {@link CommentsPart}, right after the use case. The memes part's use cases announce themselves.
 * The asymmetry is the product's, and this suite does not even it out.
 */
public final class CommentAnnouncements implements CommentEvents {

    public record Announcement(String memeId, List<String> commentIds) {
    }

    private final List<Announcement> announced = new ArrayList<>();
    private final Map<String, Integer> heardUpTo = new HashMap<>();

    @Override
    public void commentsDeleted(String memeId, List<String> commentIds) {
        if (commentIds.isEmpty()) {
            throw new IllegalArgumentException("an empty announcement carries no fact");
        }
        announced.add(new Announcement(memeId, List.copyOf(commentIds)));
    }

    public List<Announcement> announced() {
        return List.copyOf(announced);
    }

    /** The announcements about this meme, however many there were. */
    public List<Announcement> about(String memeId) {
        return announced.stream().filter(a -> a.memeId().equals(memeId)).toList();
    }

    /** The announcements this listener has not heard yet; from now on it has. */
    public List<Announcement> newFor(String listener) {
        int from = heardUpTo.getOrDefault(listener, 0);
        heardUpTo.put(listener, announced.size());
        return List.copyOf(announced.subList(from, announced.size()));
    }
}
