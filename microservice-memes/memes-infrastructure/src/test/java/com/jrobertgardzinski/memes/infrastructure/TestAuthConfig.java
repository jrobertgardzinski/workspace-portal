package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;
import java.util.Set;

/**
 * Test double for the security integration: the gate accepts one well-known token and maps it to
 * one well-known user, so tests need no running microservice-security. The real HTTP gate is
 * exercised end to end by the workspace's compose smoke test.
 */
@TestConfiguration
public class TestAuthConfig {

    public static final String VALID_TOKEN = "test-token";
    public static final String SIGNED_IN_USER = "alice@example.com";
    public static final UserId SIGNED_IN_USER_ID = UserId.of("11111111-1111-4111-8111-111111111111");
    public static final String SECOND_TOKEN = "test-token-bob";
    public static final String SECOND_USER = "bob@example.com";
    public static final UserId SECOND_USER_ID = UserId.of("22222222-2222-4222-8222-222222222222");
    public static final String MODERATOR_TOKEN = "test-token-mod";
    public static final String MODERATOR_USER = "mod@example.com";
    public static final UserId MODERATOR_USER_ID = UserId.of("33333333-3333-4333-8333-333333333333");
    public static final String ADMIN_TOKEN = "test-token-admin";
    public static final String ADMIN_USER = "admin@example.com";
    public static final UserId ADMIN_USER_ID = UserId.of("44444444-4444-4444-8444-444444444444");
    /** Alice's id, signing in after a change of address: the same person. */
    public static final String RENAMED_TOKEN = "test-token-alice-renamed";
    public static final String RENAMED_USER = "alice.new@example.com";
    /** Alice's old address under a brand-new account: somebody else. */
    public static final String IMPOSTOR_TOKEN = "test-token-impostor";

    @Bean
    @Primary
    SecurityAuthenticationGate stubSecurityAuthenticationGate() {
        return token -> switch (token == null ? "" : token) {
            case VALID_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Optional.of(SIGNED_IN_USER_ID), Set.of("USER")));
            case RENAMED_TOKEN -> Optional.of(new Caller(RENAMED_USER, Optional.of(SIGNED_IN_USER_ID), Set.of("USER")));
            case SECOND_TOKEN -> Optional.of(new Caller(SECOND_USER, Optional.of(SECOND_USER_ID), Set.of("USER")));
            case MODERATOR_TOKEN -> Optional.of(new Caller(MODERATOR_USER, Optional.of(MODERATOR_USER_ID), Set.of("USER", "MODERATOR")));
            case ADMIN_TOKEN -> Optional.of(new Caller(ADMIN_USER, Optional.of(ADMIN_USER_ID), Set.of("USER", "ADMIN")));
            case IMPOSTOR_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Optional.of(UserId.random()), Set.of("USER")));
            default -> Optional.empty();
        };
    }

    /**
     * The names security would show for the test accounts: the masked address, as the real
     * directory answers it. An id outside this list is an account security no longer knows.
     */
    @Bean
    @Primary
    com.jrobertgardzinski.authors.AuthorDirectory stubAuthorDirectory() {
        java.util.Map<UserId, com.jrobertgardzinski.authors.AuthorName> known = java.util.Map.of(
                SIGNED_IN_USER_ID, new com.jrobertgardzinski.authors.AuthorName(masked(SIGNED_IN_USER)),
                SECOND_USER_ID, new com.jrobertgardzinski.authors.AuthorName(masked(SECOND_USER)),
                MODERATOR_USER_ID, new com.jrobertgardzinski.authors.AuthorName(masked(MODERATOR_USER)),
                ADMIN_USER_ID, new com.jrobertgardzinski.authors.AuthorName(masked(ADMIN_USER)));
        return ids -> {
            java.util.Map<UserId, com.jrobertgardzinski.authors.AuthorName> found = new java.util.HashMap<>();
            for (UserId id : ids) {
                if (known.containsKey(id)) {
                    found.put(id, known.get(id));
                }
            }
            return found;
        };
    }

    private static String masked(String address) {
        return address.charAt(0) + "***@" + address.substring(address.indexOf('@') + 1);
    }
}
