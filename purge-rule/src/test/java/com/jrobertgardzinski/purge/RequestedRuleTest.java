package com.jrobertgardzinski.purge;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reading two participants had word for word. It is one type now, so the four answers below are
 * the same answers on every axis — which they were not quite: the copies differed in one word of one
 * log line, and one word of one log line is how a saga comes to do two different things.
 */
@Epic("Architecture")
@Feature("The rule a closure states")
class RequestedRuleTest {

    private static final boolean ADMIN = true;
    private static final boolean SELF = false;

    @Test
    @DisplayName("a self-closure deletes, whatever it carries and whatever the deployment prefers")
    void a_self_closure_always_deletes() {
        RequestedRule requested = RequestedRule.of(SELF, Optional.of("ANONYMIZE_AUTHOR"), "memes");

        // the right to erasure is not a preference: the exceptions to it are enumerated by law, and
        // "this one is popular" is not among them
        assertEquals(Optional.of(new PurgeRule.Delete()), requested.rule());
        assertTrue(requested.complaint().orElseThrow().contains("memes"),
                "the axis says which of its own rules it ignored");
    }

    @Test
    @DisplayName("a self-closure that carried nothing has nothing to complain about")
    void a_self_closure_without_a_rule_is_silent() {
        RequestedRule requested = RequestedRule.of(SELF, Optional.empty(), "comments");

        assertEquals(Optional.of(new PurgeRule.Delete()), requested.rule());
        assertEquals(Optional.empty(), requested.complaint());
    }

    @Test
    @DisplayName("an administrator's rule is honoured")
    void an_administrators_rule_is_honoured() {
        RequestedRule requested =
                RequestedRule.of(ADMIN, Optional.of("KEEP_POPULAR_ANONYMIZED:100"), "memes");

        assertEquals(Optional.of(new PurgeRule.KeepPopularAnonymized(100)), requested.rule());
        assertEquals(Optional.empty(), requested.complaint());
    }

    @Test
    @DisplayName("a closure that states nothing leaves the deployment its own default")
    void nothing_stated_is_not_delete() {
        RequestedRule requested = RequestedRule.of(ADMIN, Optional.empty(), "comments");

        // EMPTY, not DELETE: absent is not a decision, and the service's own default is
        assertEquals(Optional.empty(), requested.rule());
        assertEquals(Optional.empty(), requested.complaint());
    }

    @Test
    @DisplayName("an unreadable rule falls back to the default, and is reported without its text")
    void an_unreadable_rule_falls_back() {
        RequestedRule requested = RequestedRule.of(ADMIN,
                Optional.of("KEEP_POPULAR_ANONYMIZED:tell me a secret, leaver@example.com"),
                "comments");

        assertEquals(Optional.empty(), requested.rule(),
                "an operator's typo must not be read as consent to destroy more than was asked");
        String complaint = requested.complaint().orElseThrow();
        assertTrue(complaint.contains("unparseable comments purge rule"));
        // the text arrives from the leaver's own wizard, so it is never in the sentence a caller
        // logs — the shape of it is (PurgeRule.parse's own promise)
        assertFalse(complaint.contains("leaver@example.com"));
    }
}
