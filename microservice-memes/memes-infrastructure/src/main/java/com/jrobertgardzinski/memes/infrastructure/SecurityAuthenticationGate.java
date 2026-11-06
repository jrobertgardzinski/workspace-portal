package com.jrobertgardzinski.memes.infrastructure;

import java.util.Optional;

/**
 * Boundary gate to the standalone {@code microservice-security}: resolves an access token to the
 * {@link Caller} (e-mail + roles), or empty when the token is missing, invalid or expired.
 * Implemented by an HTTP adapter in production and a stub in tests.
 */
interface SecurityAuthenticationGate {

    Optional<Caller> callerFor(String accessToken);

    /**
     * Security could not be asked at all — it did not answer, or answered that it is broken. NOT
     * the same as an empty answer, and the difference is the caller's session: an empty answer
     * means the token is no good, while this means nobody knows yet. A gate that collapses the two
     * turns an outage of security into a forced sign-out of everyone holding a valid token.
     */
    class SecurityUnavailable extends RuntimeException {

        SecurityUnavailable(Throwable cause) {
            super("microservice-security could not be asked who this token belongs to", cause);
        }
    }
}
