package com.jrobertgardzinski.memes.infrastructure.cucumber;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.restassured.response.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sign-in vocabulary shared by all features. Black-box, a session IS the bearer token: "signed in"
 * means the write-steps present {@code TestAuthConfig.VALID_TOKEN}, which the stubbed gate confirms
 * as alice. The real handshake with microservice-security is covered by the workspace's compose
 * smoke test.
 */
public class AuthSteps {

    /** The response of the latest anonymous write attempt, checked by the shared refusal step. */
    static Response lastAnonymousAttempt;

    @Given("a USER")
    public void aSignedInUser() {
        // narrative step: the write-steps attach the well-known test token
    }

    @Then("the request is refused as sign-in required")
    public void theRequestIsRefused() {
        assertEquals(401, lastAnonymousAttempt.statusCode());
        assertEquals("SIGN_IN_REQUIRED", lastAnonymousAttempt.jsonPath().getString("status"));
    }
}
