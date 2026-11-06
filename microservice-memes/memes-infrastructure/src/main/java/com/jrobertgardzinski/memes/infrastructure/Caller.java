package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;

import java.util.Optional;
import java.util.Set;

/**
 * Who is making a request, as microservice-security sees them: the signed-in e-mail and the roles
 * it reports (from {@code GET /me}). A MODERATOR or ADMIN may act on other people's content; a
 * plain USER acts only on their own.
 */
record Caller(String email, Optional<UserId> userId, Set<String> roles) {

    /** A caller whose token predates the id as subject. */
    Caller(String email, Set<String> roles) {
        this(email, Optional.empty(), roles);
    }

    static Optional<UserId> userIdFrom(String subject) {
        try {
            return Optional.of(UserId.of(subject));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }


    private static final Set<String> PRIVILEGED = Set.of("MODERATOR", "ADMIN");

    boolean isModerator() {
        return roles.contains("MODERATOR") || roles.contains("ADMIN");
    }

    /**
     * The MFA floor, enforced consumer-side: an under-enrolled privileged account is served as a
     * plain USER — its MODERATOR/ADMIN roles are withheld until security reports it compliant, so
     * every existing role check enforces the floor with no per-endpoint code.
     */
    static Set<String> withMfaFloor(Set<String> roles, boolean mfaCompliant) {
        if (mfaCompliant || roles.stream().noneMatch(PRIVILEGED::contains)) {
            return roles;
        }
        return roles.stream().filter(role -> !PRIVILEGED.contains(role))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
