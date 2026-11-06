package com.jrobertgardzinski.memes.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Production {@link SecurityAuthenticationGate}: asks {@code microservice-security}'s protected
 * {@code GET /me} who the token belongs to (200 → the e-mail; 401 → empty; no answer at all →
 * {@link SecurityUnavailable}, which is a different thing and must stay one). The same pattern
 * security itself uses towards microservice-email — services trust each other over HTTP, never by
 * sharing a database.
 */
@Component
@ConditionalOnProperty(name = "security.verify", havingValue = "introspect", matchIfMissing = true)
class HttpSecurityAuthenticationGate implements SecurityAuthenticationGate {

    private final RestClient securityService;

    HttpSecurityAuthenticationGate(@Value("${security.url}") String securityUrl) {
        // bounded waits: this gate sits on EVERY request carrying a token, so a hung security
        // service must surface as a refusal, not as request threads piling up behind it.
        //
        // JdkClientHttpRequestFactory, not SimpleClientHttpRequestFactory. The two timeouts read
        // like a bounded wait and are not one: SimpleClientHttpRequestFactory hands them to
        // HttpURLConnection, whose connect timeout starts AFTER the name is resolved and whose
        // read timeout is per read() rather than per exchange. A DNS server that accepts the query
        // and never answers therefore blocks the request thread for the resolver's own timeout —
        // minutes, on a default glibc — and a peer that keeps sending SOMETHING resets the read
        // timeout with every byte, so an answer trickled over a minute costs a minute. No amount
        // of tuning these two numbers changes either. The JDK client arms its timeout before the
        // name is resolved and disarms it when the answer arrives, so resolution, connect and wait
        // are all inside the five seconds. Same conversion as the comments twin (c33e182).
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.securityService = RestClient.builder()
                .baseUrl(securityUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Optional<Caller> callerFor(String accessToken) {
        try {
            String cid = MDC.get("cid");
            Map<?, ?> body = securityService.get().uri("/me")
                    .header("Authorization", "Bearer " + accessToken)
                    .headers(h -> { if (cid != null) h.add("X-Correlation-Id", cid); })   // trace across services
                    .retrieve().body(Map.class);
            String email = body == null ? null : (String) body.get("email");
            if (email == null) {
                return Optional.empty();
            }
            Set<String> roles = body.get("roles") instanceof Collection<?> raw
                    ? raw.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet())
                    : Set.of("USER");
            // the MFA floor: an under-enrolled privileged account acts as a plain USER (fail-closed
            // when the field is missing — an old security that doesn't report it withholds nothing
            // from ordinary users, only from privileged ones)
            boolean mfaCompliant = Boolean.TRUE.equals(body.get("mfaCompliant"));
            Optional<com.jrobertgardzinski.identity.UserId> userId = body.get("id") instanceof String id
                    ? Caller.userIdFrom(id) : Optional.empty();
            return Optional.of(new Caller(email, userId, Caller.withMfaFloor(roles, mfaCompliant)));
        } catch (HttpClientErrorException tokenRefused) {
            // security answered, and the answer is about the REQUEST (401 for a token it does not
            // know). This is the only way to be "not signed in": somebody was asked and said no
            return Optional.empty();
        } catch (RestClientException securityCouldNotBeAsked) {
            // no usable answer — connect refused, timed out, name unresolved, security's own 5xx.
            // Answering "empty" here made an outage of ANOTHER service indistinguishable from an
            // expired session, and the gallery signs a visitor out on the 401 that follows. An
            // unreachable security is an availability incident, not a revocation (the same rule
            // the shared offline verifier writes down for an unreachable JWKS)
            throw new SecurityUnavailable(securityCouldNotBeAsked);
        }
    }
}
