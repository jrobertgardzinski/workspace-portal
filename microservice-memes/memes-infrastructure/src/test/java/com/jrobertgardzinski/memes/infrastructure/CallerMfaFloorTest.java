package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The consumer-side MFA floor: privileged roles are withheld from an under-enrolled account. */
@Epic("Infrastructure")
@Feature("Authentication gate")
@Story("MFA floor")
class CallerMfaFloorTest {

    @Test
    @DisplayName("an under-enrolled moderator is served as a plain USER")
    void strips_privileged_roles_when_not_compliant() {
        assertEquals(Set.of("USER"),
                Caller.withMfaFloor(Set.of("USER", "MODERATOR"), false));
        assertEquals(Set.of("USER"),
                Caller.withMfaFloor(Set.of("USER", "ADMIN", "MODERATOR"), false));
    }

    @Test
    @DisplayName("a compliant moderator keeps every role")
    void keeps_roles_when_compliant() {
        assertEquals(Set.of("USER", "MODERATOR"),
                Caller.withMfaFloor(Set.of("USER", "MODERATOR"), true));
    }

    @Test
    @DisplayName("an ordinary user is untouched either way — the floor binds only privilege")
    void plain_user_is_untouched() {
        assertEquals(Set.of("USER"), Caller.withMfaFloor(Set.of("USER"), false));
        assertEquals(Set.of("USER"), Caller.withMfaFloor(Set.of("USER"), true));
    }
}
