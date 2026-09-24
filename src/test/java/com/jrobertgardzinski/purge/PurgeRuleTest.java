package com.jrobertgardzinski.purge;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar both content services speak, stated once. Two suites used to say this — one in the
 * gallery, one in the comment threads, and the second called itself a CONTRACT test because it was
 * pinning a twin it could not see. There is no twin now.
 */
@Epic("Config")
@Feature("Purge rules")
class PurgeRuleTest {

    @Test
    @DisplayName("speaks the shared vocabulary: DELETE, ANONYMIZE_AUTHOR, KEEP_POPULAR_ANONYMIZED:n")
    void parses_the_vocabulary() {
        assertEquals(new PurgeRule.Delete(), PurgeRule.parse("DELETE"));
        assertEquals(new PurgeRule.AnonymizeAuthor(), PurgeRule.parse("ANONYMIZE_AUTHOR"));
        assertEquals(new PurgeRule.KeepPopularAnonymized(100), PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:100"));
        assertThrows(IllegalArgumentException.class, () -> PurgeRule.parse("EXPLODE"));
        assertThrows(IllegalArgumentException.class, () -> PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:abc"));
        assertThrows(IllegalArgumentException.class, () -> PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:0"));
        assertThrows(IllegalArgumentException.class, () -> PurgeRule.parse(null));
    }

    @Test
    @DisplayName("asText is the inverse of parse — the vocabulary round-trips")
    void renders_back_to_the_vocabulary() {
        for (String text : new String[]{"DELETE", "ANONYMIZE_AUTHOR", "KEEP_POPULAR_ANONYMIZED:100"}) {
            assertEquals(text, PurgeRule.parse(text).asText());
        }
    }

    @Test
    @DisplayName("popularity decides what a rule keeps")
    void keeps_by_score() {
        assertFalse(new PurgeRule.Delete().keeps(1000));
        assertTrue(new PurgeRule.AnonymizeAuthor().keeps(-5));
        assertTrue(new PurgeRule.KeepPopularAnonymized(100).keeps(100));
        assertFalse(new PurgeRule.KeepPopularAnonymized(100).keeps(99));
    }

    @Test
    @DisplayName("a refusal never repeats what it was given — the text is the leaver's own typing")
    void the_refusal_does_not_echo_the_input() {
        String typedByTheLeaver = "delete everything, my number is 601234567, "
                + "alice@example.com\nERROR forged line";

        String message = assertThrows(IllegalArgumentException.class,
                () -> PurgeRule.parse(typedByTheLeaver)).getMessage();

        assertFalse(message.contains("601234567"), message);
        assertFalse(message.contains("alice@example.com"), message);
        assertFalse(message.contains("\n"), "a newline in a log message forges a second line: " + message);
        assertTrue(message.contains(String.valueOf(typedByTheLeaver.length())),
                "the length is what makes it investigable: " + message);
    }

    @Test
    @DisplayName("the shape says whether it was ALMOST a rule, and nothing else")
    void the_shape_keeps_vocabulary_only() {
        assertEquals("DELETE?", PurgeRule.shapeOf("DELETE please, 601234567"));
        assertEquals("?ANONYMIZE_AUTHOR?", PurgeRule.shapeOf("maybe ANONYMIZE_AUTHOR or not"));
        assertEquals("KEEP_POPULAR_ANONYMIZED:10", PurgeRule.shapeOf("KEEP_POPULAR_ANONYMIZED:10"));
        assertEquals("?", PurgeRule.shapeOf("+48 601 234 567"));
        assertEquals("?", PurgeRule.shapeOf(""));
        assertEquals("?", PurgeRule.shapeOf(null));
    }

    @Test
    @DisplayName("a threshold longer than four digits is not vocabulary — that is a telephone")
    void a_long_number_is_not_a_threshold() {
        // parse() would accept 99999; the SHAPE deliberately will not echo it. A popularity
        // threshold is a handful of votes; ten digits is somebody's phone number.
        assertEquals(new PurgeRule.KeepPopularAnonymized(99999),
                PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:99999"));
        assertEquals("?", PurgeRule.shapeOf("KEEP_POPULAR_ANONYMIZED:0601234567 junk"));
    }
}
