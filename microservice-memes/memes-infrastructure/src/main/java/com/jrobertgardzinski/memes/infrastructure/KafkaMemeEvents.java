package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.domain.MemeEvents;
import com.jrobertgardzinski.deletion.DeletionMessages;
import com.jrobertgardzinski.deletion.MemeDeleted;
import com.jrobertgardzinski.outbox.OutboxEvent;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes meme lifecycle events on {@code memes-events}; microservice-comments drops a deleted
 * meme's thread on MEME_DELETED and microservice-user-collections drops every saved reference to
 * it. Active where a broker exists; the no-op stand-in serves tests.
 *
 * <p>The durability is a transactional outbox, and since round 10 that outbox is the shared kernel
 * library ({@code com.jrobertgardzinski:transactional-outbox} plus its Spring adapter) rather than
 * two classes of this service's own. It is the same code, not a same-shaped copy: the library was
 * extracted FROM this implementation, which is why every property rounds 5-9 hardened here still
 * has its test in this repository — a green suite is what proves the abstraction ate none of them.
 *
 * <p>What {@link SpringOutbox#announce} does with the event built below, in one call:
 * <ul>
 *   <li>writes the row in the SAME transaction as the delete/purge announcing it (the use cases
 *       announce from inside their transactional decorators, {@link TransactionalDeleteMeme} and
 *       {@link TransactionalPurgeUserContent}), so a rollback discards the announcement with the
 *       teardown — no row, no event;</li>
 *   <li>parks the FIRST delivery attempt on the commit, and makes it without waiting for the
 *       broker's acknowledgement — the mark rides the confirmation callback instead. A crash
 *       between commit and send, or a broker outage during it, leaves the row unpublished for the
 *       library's republisher (wired in {@link MemeOutboxConfig}), which waits for the
 *       acknowledgement before marking: delivered-first, and the republisher — not the first
 *       attempt — is the guarantee.</li>
 * </ul>
 *
 * <p>This class is left with exactly the two jobs the library cannot do for it. It <strong>names
 * the topic</strong> (pinned by {@link MemeDeletedTopicTest} and, across repositories, by the
 * MEME_DELETED pact), and it <strong>builds the payload around the event id the library minted
 * first</strong>. That second one is a documented division of labour: the library keeps the payload
 * opaque on purpose — never parsed, never re-serialised, so a redelivery is byte-identical to the
 * first attempt by construction — which is exactly why it cannot inject the id into the JSON
 * itself. And the id must be in there, because redelivery is possible by design (a confirmed send
 * whose mark did not land) and the consumers recognise the duplicate by it.
 *
 * @see KafkaMemeDispatch the send itself, and the two Kafka-side halves of the library's contract
 */
class KafkaMemeEvents implements MemeEvents {

    /**
     * Where the deletion cascade starts. Two other repositories subscribe to this literal — see
     * {@link MemeDeletedTopicTest}, which names them, before changing it.
     */
    static final String TOPIC = "memes-events";

    static final String MEME_DELETED = DeletionMessages.MEME_DELETED;

    private final SpringOutbox outbox;

    KafkaMemeEvents(SpringOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void memeDeleted(String memeId) {
        outbox.announce(deletionOf(memeId));
    }

    /**
     * The event as it will be stored AND as it will be sent — the row is the record.
     *
     * <p>Everything is captured NOW, while the announcing thread's context is certainly still
     * around: the correlation id comes from the MDC here and is stored in the row, so a
     * republication hours later (scheduler thread, empty MDC) still carries the trace of the
     * request that deleted the meme.
     *
     * <p>The event id is minted BEFORE the payload and pasted INTO it, so the row's key and the
     * {@code eventId} a consumer deduplicates on are the same string, for the original attempt and
     * for every redelivery of that row. Package-private and static because the contract tests build
     * the real announcement from it without a database — the payload's shape is what they verify,
     * and a table is not part of that shape.
     */
    static OutboxEvent deletionOf(String memeId) {
        String eventId = OutboxEvent.newId();
        // the field set is the library's: add a field to the agreement and every service that
        // speaks it gains the field in the same commit. The envelope id is pasted in here,
        // spelled eventId as the pacts pin it — COMMENTS_DELETED spells the same thing id, and
        // the vocabulary keeps both names rather than pretending otherwise
        Map<String, Object> fields = new LinkedHashMap<>(new MemeDeleted(memeId).fields());
        fields.put(DeletionMessages.Field.EVENT_ID, eventId);
        String payload = json(fields);
        // keyed by the meme, so its whole cascade stays on one partition: a consumer never sees a
        // later hop of the cascade before this one
        return new OutboxEvent(eventId, TOPIC, MEME_DELETED, memeId,
                MDC.get(CorrelationIdFilter.MDC_KEY), payload);
    }

    /**
     * Three string fields, written by hand for the reason they always were: this adapter has no
     * ObjectMapper, and a payload the outbox stores verbatim must be byte-identical on every
     * redelivery — which a hand-built string of known fields is by construction.
     */
    private static String json(Map<String, Object> fields) {
        StringBuilder payload = new StringBuilder("{");
        fields.forEach((field, value) -> payload
                .append(payload.length() > 1 ? "," : "")
                .append('"').append(field).append("\":\"").append(value).append('"'));
        return payload.append('}').toString();
    }
}
