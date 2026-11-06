package com.jrobertgardzinski.memes.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Browsing is public, contributing requires signing in: every write ({@code POST} under
 * {@code /memes}) must carry a bearer token that {@code microservice-security} confirms; the
 * confirmed e-mail is published as the {@link #AUTHENTICATED_USER} request attribute (the comment
 * author, for one, comes from there — never from the request body). Reads pass through untouched —
 * except under {@code /admin/**}, where even reads require signing in (the controllers there
 * additionally demand the ADMIN role).
 */
@Component
class RequireSignInFilter extends OncePerRequestFilter {

    static final String AUTHENTICATED_USER = "authenticatedUser";
    static final String AUTHENTICATED_USER_ID = "authenticatedUserId";
    static final String AUTHENTICATED_ROLES = "authenticatedRoles";

    private final SecurityAuthenticationGate gate;

    RequireSignInFilter(SecurityAuthenticationGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // DECODED, because Spring routes on decoded path segments and this gate does not: a request
        // for /%61dmin/purge-policy reaches AdminController, while a raw-URI test reads it as
        // "not ours" and lets it past with `admin` false — no sign-in demanded AND the admin
        // branch missed. Decoding the whole URI makes the gate slightly WIDER than the router (a
        // %2F reads as a separator here and not there), which is the safe direction for a gate.
        // The comments twin was fixed the same way; neither of us had a correct filter to copy.
        String path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        boolean admin = path.startsWith("/admin");
        if (!path.startsWith("/memes") && !admin) {
            chain.doFilter(request, response);
            return;
        }
        // resolve the identity whenever a token is presented (reads use it to show "your vote");
        // only writes REQUIRE it — and everything under /admin does, reads included
        Optional<Caller> caller = Optional.empty();
        boolean securityAnswered = true;
        try {
            caller = bearerToken(request).flatMap(gate::callerFor);
        } catch (SecurityAuthenticationGate.SecurityUnavailable couldNotAsk) {
            // a public read carries on anonymously — the gallery does not go dark because the
            // sign-in service is down — but a write cannot, and must not be told to sign in again
            securityAnswered = false;
        }
        // a token without an id predates the cutover (the address as subject); it is nobody here
        caller = caller.filter(c -> c.userId().isPresent());
        caller.ifPresent(c -> {
            request.setAttribute(AUTHENTICATED_USER, c.email());
            request.setAttribute(AUTHENTICATED_USER_ID, c.userId().orElseThrow());
            request.setAttribute(AUTHENTICATED_ROLES, c.roles());
        });
        boolean write = admin || Set.of("POST", "PUT", "DELETE", "PATCH").contains(request.getMethod());
        if (write && caller.isEmpty()) {
            // 401 SIGN_IN_REQUIRED is a statement about the CALLER'S SESSION, and the gallery acts
            // on it: api.ts tries to renew the token, fails against the same dead service and logs
            // the visitor out. Saying it when security is merely unreachable destroys a live
            // session over somebody else's outage, so an unanswered gate gets its own code — the
            // token was never judged, and a 503 leaves the session intact
            response.setStatus(securityAnswered
                    ? HttpServletResponse.SC_UNAUTHORIZED
                    : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write(securityAnswered
                    ? "{\"status\":\"SIGN_IN_REQUIRED\"}"
                    : "{\"status\":\"SECURITY_UNAVAILABLE\",\"detail\":\"the sign-in service "
                            + "could not be reached; your session is unaffected\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ")
                ? Optional.of(header.substring("Bearer ".length()))
                : Optional.empty();
    }
}
