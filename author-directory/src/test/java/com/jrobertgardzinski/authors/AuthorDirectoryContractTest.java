package com.jrobertgardzinski.authors;

import com.jrobertgardzinski.identity.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every {@link AuthorDirectory} owes its callers, whatever sits behind it. */
public abstract class AuthorDirectoryContractTest {

    protected abstract AuthorDirectory directory();

    /** Makes the directory know a person; returns their id. */
    protected abstract UserId givenAuthor(String email);

    @Test
    @DisplayName("every known id comes back with a name, and only once")
    void known_ids_have_names() {
        UserId alice = givenAuthor("alice@example.com");
        UserId bob = givenAuthor("bob@example.com");

        Map<UserId, AuthorName> names = directory().namesOf(List.of(alice, bob, alice));

        assertEquals(2, names.size());
        assertFalse(names.get(alice).display().isBlank());
        assertFalse(names.get(bob).display().isBlank());
    }

    @Test
    @DisplayName("an id nobody holds is absent, not an error")
    void unknown_ids_are_absent() {
        UserId alice = givenAuthor("alice@example.com");
        UserId nobody = UserId.random();

        Map<UserId, AuthorName> names = directory().namesOf(List.of(alice, nobody));

        assertTrue(names.containsKey(alice));
        assertFalse(names.containsKey(nobody));
    }

    @Test
    @DisplayName("nothing asked, nothing answered")
    void empty_in_empty_out() {
        assertEquals(Map.of(), directory().namesOf(List.of()));
    }
}
