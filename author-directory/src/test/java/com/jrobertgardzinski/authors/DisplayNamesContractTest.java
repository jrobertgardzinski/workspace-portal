package com.jrobertgardzinski.authors;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.identity.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The consumer's half of the display-name contract: this library is THE consumer of
 * {@code GET /users?ids=}; security verifies the committed pact against its real controller.
 * The ids are fixed so the provider can seed exactly these accounts.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "microservice-security", pactVersion = PactSpecVersion.V3)
class DisplayNamesContractTest {

    static final String ALICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    static final String BOB = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    static final String NOBODY = "00000000-0000-0000-0000-000000000000";

    @Pact(consumer = "author-directory")
    RequestResponsePact displayNames(PactDslWithProvider builder) {
        return builder
                .given("accounts exist for alice and bob")
                .uponReceiving("a lookup of two accounts and one id nobody holds")
                .path("/users")
                .query("ids=" + ALICE + "," + BOB + "," + NOBODY)
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonArray()
                        .object().stringValue("id", ALICE).stringValue("displayName", "a***@example.com").closeObject()
                        .object().stringValue("id", BOB).stringValue("displayName", "b***@example.com").closeObject())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "displayNames")
    void theAdapterReadsWhatSecurityAnswers(MockServer security) {
        AuthorDirectory directory = SecurityAuthorDirectory.overHttp(security.getUrl(), new ObjectMapper(),
                Duration.ofMinutes(1), Clock.systemUTC(), failure -> {
                    throw new AssertionError(failure);
                });

        Map<UserId, AuthorName> names = directory.namesOf(
                List.of(UserId.of(ALICE), UserId.of(BOB), UserId.of(NOBODY)));

        assertEquals(Map.of(UserId.of(ALICE), new AuthorName("a***@example.com"),
                UserId.of(BOB), new AuthorName("b***@example.com")), names);
    }
}
