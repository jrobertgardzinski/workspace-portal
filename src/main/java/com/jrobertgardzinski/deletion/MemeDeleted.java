package com.jrobertgardzinski.deletion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The cascade's first hop: a meme is gone. Every hop that hears it drops what it holds of that
 * meme — there is nothing else in the message, because there is nothing else to say.
 *
 * @param memeId the meme that went, always a usable id: {@link #of(String)} is the only way in
 */
public record MemeDeleted(String memeId) {

    /**
     * The parsed event, or nothing at all when it names no meme this cascade could act on.
     *
     * <p>A deletion that names nothing cannot be fixed by any retry, so every hop drops it — and
     * the rule lived in one adapter's private method while the other adapter had no rule at all.
     * It is the EVENT's contract, checked at the boundary, and it does not judge ids already
     * stored: those are opaque by design.
     */
    public static Optional<MemeDeleted> of(String memeId) {
        return Ids.usable(memeId) ? Optional.of(new MemeDeleted(memeId)) : Optional.empty();
    }

    /**
     * The message, field by field, for whatever mapper the caller happens to hold — a map, because
     * this library has no dependencies and no opinion about serialisation.
     *
     * <p>Insertion-ordered, and unmodifiable rather than copied into a {@code Map.of}: one
     * adapter builds the payload by hand, with no mapper in the module, so the field order has
     * to be the vocabulary's and not a hash's.
     *
     * <p>No {@code version} and no {@code eventId}: this message has never carried a version, and
     * the envelope id is minted by the outbox before the payload exists, so the adapter pastes it
     * in. Both are stated here rather than discovered by whoever changes the payload next.
     */
    public Map<String, Object> fields() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(DeletionMessages.Field.TYPE, DeletionMessages.MEME_DELETED);
        message.put(DeletionMessages.Field.MEME_ID, memeId);
        return Collections.unmodifiableMap(message);
    }
}
