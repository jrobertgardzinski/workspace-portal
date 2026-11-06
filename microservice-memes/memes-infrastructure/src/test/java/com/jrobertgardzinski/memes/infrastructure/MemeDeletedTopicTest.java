package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where MEME_DELETED goes — the first link of the deletion cascade, and the one the audit of 26.07
 * found nothing was guarding.
 *
 * <p>Its finding, in full: no assertion anywhere named a TOPIC. Rename it on one side only — a typo,
 * a copied constant, a "-v2" suffix — and this service publishes into the void while two other
 * services wait forever. No comment thread is dropped, no saved reference is cleaned up, nothing
 * throws, and every signal the portal has stays green (a green suite, {@code docker compose ps},
 * {@code up=1} in Prometheus), because every process involved is perfectly healthy. The blast radius
 * is a portal that keeps showing deleted memes' comments and collections' entries; the diagnosis
 * starts from a user complaint, not from CI.
 *
 * <p>So the name is pinned to a LITERAL here, against the record the REAL producer builds, and to
 * the same literal in each consuming repository. They cannot share a constant — separate git
 * repositories, released and versioned apart, and a shared library for one string would couple their
 * release cycles for nothing — so the literal is duplicated deliberately and this comment says where
 * its twins are:
 *
 * <ul>
 *   <li><b>microservice-comments</b> — {@code MemesEventsListener}'s {@code @KafkaListener(topics =
 *       "memes-events")}, pinned in
 *       {@code src/test/java/com/jrobertgardzinski/comments/infrastructure/CascadeTopicNamesTest.java}
 *       (it drops the dead meme's thread);</li>
 *   <li><b>microservice-user-collections</b> — {@code CascadeConsumer#MEMES_TOPIC}, pinned in
 *       {@code src/test/java/com/jrobertgardzinski/collections/infrastructure/CascadeTopicNamesTest.java}
 *       (it drops every saved reference to the meme).</li>
 * </ul>
 *
 * <p><b>Changing the string here REQUIRES the matching change in BOTH repositories, in the files
 * named above.</b> That is the price of separate repositories, and a test failing on every side is
 * what makes the price visible instead of silent.
 *
 * <p>The name is also checked ACROSS the repositories rather than only inside each: the MEME_DELETED
 * pact recorded by microservice-user-collections carries the topic as message metadata, and
 * {@link MemeDeletedPactProviderTest} answers it with the real record's {@code topic()}.
 *
 * <p>Which is why the record here comes from that class's fixture: the pact and this test must
 * assert the SAME real object, or one of them could go on passing while the other describes a
 * producer that no longer exists. Unlike the pact test this one has no {@code @EnabledIf} — it needs
 * no neighbouring checkout, so it runs in every layout, including a solo one.
 */
@Epic("Infrastructure")
@Feature("Meme deleted announcement")
class MemeDeletedTopicTest {

    /** See the class comment: the twin literals live in comments and user-collections. */
    private static final String MEMES_EVENTS = "memes-events";

    @Test
    @DisplayName("the deletion announcement is published on the topic both consumers subscribe to")
    void the_announcement_goes_to_the_agreed_topic() {
        // the topic off the REAL ProducerRecord, not the constant: the name is written into the
        // outbox event and only later mapped onto the record, so a topic mangled anywhere on that
        // way to the broker would still pass a constant check
        ProducerRecord<String, String> announced =
                MemeDeletedPactProviderTest.realAnnouncement(UUID.randomUUID().toString());

        assertEquals(MEMES_EVENTS, announced.topic(),
                "microservice-comments and microservice-user-collections both subscribe here —"
                        + " see the class comment before changing this");
        assertEquals(MEMES_EVENTS, KafkaMemeEvents.TOPIC,
                "and the constant the outbox event is written with must say the same");
    }

    @Test
    @DisplayName("the announcement is keyed by the meme, so its whole cascade stays ordered")
    void the_announcement_is_keyed_by_the_meme() {
        // the other half of the transport contract: microservice-comments announces COMMENTS_DELETED
        // under the SAME key, so a consumer never learns the comments' fate before the meme's
        String memeId = UUID.randomUUID().toString();

        assertEquals(memeId, MemeDeletedPactProviderTest.realAnnouncement(memeId).key());
    }
}
