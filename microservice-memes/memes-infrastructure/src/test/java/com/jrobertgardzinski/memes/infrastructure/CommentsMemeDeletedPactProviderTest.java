package com.jrobertgardzinski.memes.infrastructure;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The same announcement, verified against the OTHER consumer's expectations.
 *
 * <p>MEME_DELETED has two readers — user-collections drops the dangling favourites,
 * microservice-comments drops the whole comment thread — and until 2026-07-29 only the first had a
 * pact. That made the cascade's first hop the one inter-service contract in the estate with nothing
 * guarding it: rename or retype {@code memeId} here and comment threads quietly stop being deleted,
 * with every suite on both sides still green.
 *
 * <p>A second class rather than a second folder on the existing one, because {@code @PactFolder}
 * takes exactly one path — and each consumer keeps its pacts in its own repository.
 */
@Epic("Contract")
@Feature("Meme deleted announcement")
@Story("Comments consumer")
@Provider("microservice-memes")
@PactFolder(CommentsMemeDeletedPactProviderTest.PACT_FOLDER)
@EnabledIf(value = "consumerPactCheckedOut",
        disabledReason = "microservice-comments' memes pact is not checked out next to this repo")
class CommentsMemeDeletedPactProviderTest {

    /** Relative to the MODULE directory, which is what surefire makes the working directory. */
    static final String PACT_FOLDER = "../../microservice-comments/pacts";

    static final String PACT_FILE = "microservice-comments-microservice-memes.json";

    static boolean consumerPactCheckedOut() {
        return Files.isRegularFile(Path.of(PACT_FOLDER, PACT_FILE));
    }

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget(List.of("com.jrobertgardzinski")));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void theAnnouncementShapeAndTopicTheThreadCascadeReliesOn(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // The description differs from the sibling provider test's on purpose: the verifier scans the
    // package for @PactVerifyProvider methods BY DESCRIPTION, so two consumers using the same
    // wording makes both verifications ambiguous and both fail. Each consumer names the event as it
    // sees it, and the bytes below are still the one real producer's.
    @au.com.dius.pact.provider.PactVerifyProvider("a meme deleted announcement for the comment thread")
    public MessageAndMetadata aMemeDeletedAnnouncement() {
        // the REAL producer's record, built by the same helper the sibling provider test uses —
        // one place decides what a MEME_DELETED looks like, and both consumers verify against it
        ProducerRecord<String, String> record =
                MemeDeletedPactProviderTest.realAnnouncement(UUID.randomUUID().toString());
        return new MessageAndMetadata(
                record.value().getBytes(StandardCharsets.UTF_8),
                Map.of("contentType", "application/json", "topic", record.topic()));
    }
}
