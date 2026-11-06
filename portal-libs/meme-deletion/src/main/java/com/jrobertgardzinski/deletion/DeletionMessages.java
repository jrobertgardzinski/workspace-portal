package com.jrobertgardzinski.deletion;

/**
 * Every message the meme-deletion CASCADE sends, by the name it goes by on the wire — and, in the
 * order below, the whole choreography.
 *
 * <ol>
 *   <li>microservice-memes states {@link #MEME_DELETED}: a meme is gone.</li>
 *   <li>microservice-comments drops that meme's thread and states {@link #COMMENTS_DELETED},
 *       naming every comment that went with it — unless there were none, in which case it states
 *       nothing, because an empty announcement carries no fact.</li>
 *   <li>microservice-user-collections hears both and drops every saved reference to the meme and
 *       to its comments.</li>
 * </ol>
 *
 * <p>Nobody orchestrates this and nothing is confirmed: each service reacts to the hop before it.
 * There is no compensation either — a hop that gives up leaves dead rows behind, which the UI
 * already renders as such. That is the accepted worst case, not an omission.
 *
 * <p>Constants rather than an enum, for the reason {@code ClosureMessages} gives: they cross three
 * services and three frameworks, they are read out of JSON, and a consumer meeting a name from a
 * newer producer must be able to ignore it rather than fail to deserialise a value object.
 */
public final class DeletionMessages {

    /** microservice-memes → everyone: this meme is gone. Carries {@code memeId}. */
    public static final String MEME_DELETED = "MEME_DELETED";

    /** microservice-comments → everyone: the thread went with it, and here is what was in it. */
    public static final String COMMENTS_DELETED = "COMMENTS_DELETED";

    private DeletionMessages() {
    }

    /** The fields these messages carry that more than one service reads by name. */
    public static final class Field {

        /** The message's own name — the one every consumer switches on. */
        public static final String TYPE = "type";

        /** The meme the whole cascade is about, and the partition key of every hop. */
        public static final String MEME_ID = "memeId";

        /** The comments that went down with the meme, as an array of ids. */
        public static final String COMMENT_IDS = "commentIds";

        /**
         * The envelope id of a {@link #COMMENTS_DELETED}, which a consumer deduplicates on.
         *
         * <p>The two messages spell this field differently — {@code id} here, {@link #EVENT_ID} on
         * {@link #MEME_DELETED} — and the vocabulary keeps BOTH names rather than pretending
         * otherwise. Unifying the wire would break the MEME_DELETED pacts, which pin
         * {@code eventId}; that is a migration of its own and deliberately not this library's.
         */
        public static final String ID = "id";

        /** The envelope id of a {@link #MEME_DELETED}. See {@link #ID} for why there are two. */
        public static final String EVENT_ID = "eventId";

        /** Envelope version — fields are only ever ADDED within a version (workspace ADR 0004). */
        public static final String VERSION = "version";

        private Field() {
        }
    }
}
