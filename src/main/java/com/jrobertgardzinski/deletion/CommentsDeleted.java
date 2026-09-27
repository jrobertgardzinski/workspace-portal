package com.jrobertgardzinski.deletion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cascade's second hop: a meme's thread went with it, and these are the comments that went.
 *
 * <p>The meme id is the event's key and its handle in every log line — an announcement nobody can
 * trace back to a meme is one nobody can audit — so an event without a usable one is no event at
 * all. The comment ids are different: a partially mis-produced list still names refs that can
 * honestly be dropped, so the unusable ones are counted out rather than taken as reason to
 * abandon the rest.
 *
 * @param memeId     the meme whose thread was dropped, always a usable id
 * @param commentIds the comments that went, every one of them usable; possibly empty, which is a
 *                   valid event that names nothing
 * @param unusable   how many ids were dropped on the way in, for the adapter to log — never the
 *                   ids themselves, which are untrusted content
 */
public record CommentsDeleted(String memeId, List<String> commentIds, int unusable) {

    /** Envelope version — fields are only ever ADDED within version 1 (workspace ADR 0004). */
    public static final int VERSION = 1;

    public CommentsDeleted {
        commentIds = List.copyOf(commentIds);
    }

    /**
     * The parsed event, or nothing at all when it names no meme. Keeps the comment ids that are
     * usable and counts the rest.
     */
    public static Optional<CommentsDeleted> of(String memeId, List<String> ids) {
        if (!Ids.usable(memeId)) {
            return Optional.empty();
        }
        List<String> usable = new ArrayList<>();
        int unusable = 0;
        for (String id : ids == null ? List.<String>of() : ids) {
            if (Ids.usable(id)) {
                usable.add(id);
            } else {
                unusable++;
            }
        }
        return Optional.of(new CommentsDeleted(memeId, usable, unusable));
    }

    /**
     * The message, field by field. Carries the version the wire has always carried, and not the
     * envelope id: that is minted by the outbox before the payload exists, so the adapter pastes
     * it in under {@link DeletionMessages.Field#ID}.
     */
    public Map<String, Object> fields() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(DeletionMessages.Field.TYPE, DeletionMessages.COMMENTS_DELETED);
        message.put(DeletionMessages.Field.MEME_ID, memeId);
        message.put(DeletionMessages.Field.COMMENT_IDS, commentIds);
        message.put(DeletionMessages.Field.VERSION, VERSION);
        return Collections.unmodifiableMap(message);
    }
}
