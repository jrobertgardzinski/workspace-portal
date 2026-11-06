package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.ServerSocket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Sign in again" is a statement about the CALLER'S SESSION, and the gallery believes it: a 401 on
 * a request that carried a token makes {@code api.ts} renew the token, fail, and log the visitor
 * out. So this service may only say it when somebody actually judged the token.
 *
 * <p>It used to say it whenever the gate came back empty, and the gate came back empty for an
 * unreachable security as readily as for a forged token — so a restart of microservice-security
 * silently signed out everyone holding a perfectly valid one, and the logout it triggered was
 * itself a call to the service that was down. The estate's own rule, written into the shared
 * offline verifier, is the opposite: an unreachable authority is an availability incident, not a
 * revocation.
 *
 * <p>Reads are untouched: the gallery is public, so a read carrying a token during the outage is
 * served anonymously rather than refused.
 */
@Epic("Security integration")
@Feature("Token introspection")
@Story("An outage is not a revocation")
class SecurityOutageIsNotASignOutTest {

    private static final String TOKEN = "Bearer a-perfectly-good-token";

    @Test
    @DisplayName("a write while security is unreachable gets 503, and the session survives it")
    void an_unreachable_security_does_not_sign_the_visitor_out() throws Exception {
        MockHttpServletResponse response = write(token -> {
            throw new SecurityAuthenticationGate.SecurityUnavailable(new IllegalStateException("down"));
        });

        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.getStatus());
        assertTrue(response.getContentAsString().contains("SECURITY_UNAVAILABLE"),
                "the refusal names its own axis so a client can tell it from an expired session: "
                        + response.getContentAsString());
    }

    @Test
    @DisplayName("a write with a token security does not recognise is still 401 SIGN_IN_REQUIRED")
    void an_unrecognised_token_is_still_a_sign_out() throws Exception {
        MockHttpServletResponse response = write(token -> Optional.empty());

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentAsString().contains("SIGN_IN_REQUIRED"),
                response.getContentAsString());
    }

    @Test
    @DisplayName("a public read during the outage is served anonymously, not refused")
    void a_read_still_works_while_security_is_down() throws Exception {
        RequireSignInFilter filter = new RequireSignInFilter(token -> {
            throw new SecurityAuthenticationGate.SecurityUnavailable(new IllegalStateException("down"));
        });
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/memes");
        request.addHeader("Authorization", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus(), "the gallery is public and stays up");
        assertNull(chain.getRequest().getAttribute(RequireSignInFilter.AUTHENTICATED_USER),
                "nobody was identified — the read is simply anonymous");
    }

    @Test
    @DisplayName("the real gate reports an outage rather than an unknown token when nobody answers")
    void the_http_gate_distinguishes_an_outage_from_a_refusal() throws Exception {
        int nobodyListening;
        try (ServerSocket closed = new ServerSocket(0)) {
            nobodyListening = closed.getLocalPort();
        }
        HttpSecurityAuthenticationGate gate =
                new HttpSecurityAuthenticationGate("http://localhost:" + nobodyListening);

        assertThrows(SecurityAuthenticationGate.SecurityUnavailable.class,
                () -> gate.callerFor("a-perfectly-good-token"),
                "a refused connection says nothing about the token, and must not be reported as if "
                        + "it did");
    }

    /** One write request through the filter, with the gate answering however the test says. */
    private MockHttpServletResponse write(SecurityAuthenticationGate gate) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/memes/x/votes");
        request.addHeader("Authorization", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequireSignInFilter(gate).doFilter(request, response, new MockFilterChain());
        return response;
    }
}
