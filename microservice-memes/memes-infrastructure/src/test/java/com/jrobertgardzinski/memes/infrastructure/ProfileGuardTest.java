package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard on the production path, through the real listener on a real environment — the property
 * it judges is resolved by config-data processing, so a unit test on a hand-built environment would
 * pass whether or not the guard can actually see it.
 *
 * <p>No auto-configuration is involved: the source below is a bare {@code @Configuration}, so what
 * these tests boot is the environment and nothing else. The guard's decision is made before any
 * context exists, which is the whole point — it refuses the start rather than the first query.
 */
@Epic("Infrastructure")
@Feature("Deployment profile")
class ProfileGuardTest {

    @Configuration
    static class NothingButTheEnvironment {
    }

    private static SpringApplication guarded() {
        SpringApplication application = new SpringApplication(NothingButTheEnvironment.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addListeners(new ProfileGuard());
        return application;
    }

    @Test
    @DisplayName("a start that names no deployment profile is refused")
    void an_undeclared_start_is_refused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> guarded().run().close());
        assertTrue(refused.getMessage().contains("no deployment profile"), refused.getMessage());
    }

    @Test
    @DisplayName("a prod start that forgot DB_URL is refused, not quietly given an in-memory database")
    void a_prod_start_without_a_database_is_refused() {
        // the variable that selects nothing (the profile) was default-deny; the one that loses
        // every meme on the next restart defaulted silently
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> guarded().run("--spring.profiles.active=prod").close());
        assertTrue(refused.getMessage().contains("DB_URL"),
                "the refusal names the variable to set — said: " + refused.getMessage());
    }

    @Test
    @DisplayName("a prod start with a real database is exactly as welcome as before")
    void a_prod_start_with_a_database_is_fine() {
        try (ConfigurableApplicationContext started = guarded().run("--spring.profiles.active=prod",
                "--spring.datasource.url=jdbc:postgresql://db:5432/memes")) {
            assertTrue(started.isActive(), "the guard has nothing to say about a declared start");
        }
    }
}
