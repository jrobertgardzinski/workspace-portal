package com.jrobertgardzinski.memes.infrastructure;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.annotations.PactDirectory;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The consumer's half of the introspection contract: what this service's production gate relies
 * on when it asks {@code GET /me} who a bearer token belongs to — the fields it reads (email,
 * roles, mfaCompliant) on 200, and 401 for a token security does not recognise. Proven by driving
 * the REAL gate against the pact's mock. The pact lands in {@code pacts-http/} (committed,
 * separate from the message pacts — a V3 file cannot mix the two) and microservice-security's
 * provider test verifies it against the real controller with a real session.
 */
@Epic("Contract")
@Feature("Token introspection")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "microservice-security", pactVersion = PactSpecVersion.V3)
@PactDirectory("../pacts-http")
class IntrospectionContractTest {

    @Pact(consumer = "microservice-memes")
    RequestResponsePact validToken(PactDslWithProvider builder) {
        return builder
                .given("a session for a signed-in user exists")
                .uponReceiving("an introspection of a valid access token")
                .path("/me")
                .method("GET")
                .headers(Map.of("Authorization", "Bearer valid-session-token"))
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("id", "0f8fad5b-d9cb-469f-a165-70867728950e")
                        .stringType("email", "user@example.com")
                        .minArrayLike("roles", 1, PactDslJsonRootValue.stringType("USER"), 1)
                        .booleanType("mfaCompliant", true))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "validToken")
    void aRecognisedTokenBecomesTheCaller(MockServer security) {
        HttpSecurityAuthenticationGate gate = new HttpSecurityAuthenticationGate(security.getUrl());

        Optional<Caller> caller = gate.callerFor("valid-session-token");

        assertTrue(caller.isPresent());
        assertEquals("user@example.com", caller.get().email());
        assertTrue(caller.get().roles().contains("USER"));
    }

    @Pact(consumer = "microservice-memes")
    RequestResponsePact invalidToken(PactDslWithProvider builder) {
        return builder
                .uponReceiving("an introspection of an invalid token")
                .path("/me")
                .method("GET")
                .headers(Map.of("Authorization", "Bearer invalid-token"))
                .willRespondWith()
                .status(401)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "invalidToken")
    void anUnrecognisedTokenIsNobody(MockServer security) {
        HttpSecurityAuthenticationGate gate = new HttpSecurityAuthenticationGate(security.getUrl());

        assertTrue(gate.callerFor("invalid-token").isEmpty(), "the gate fails closed on 401");
    }
}
