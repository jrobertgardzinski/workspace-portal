package com.jrobertgardzinski.memes.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Epic("Config")
@Feature("Tag limits")
class TagLimitsTest {

    @Test
    @DisplayName("keeps a positive ceiling")
    void keeps_a_positive_ceiling() {
        assertEquals(8, new TagLimits(8).maxPerMeme());
    }

    @Test
    @DisplayName("zero is a ceiling, not a mistake: it turns tagging off")
    void zero_turns_tagging_off() {
        assertEquals(0, new TagLimits(0).maxPerMeme());
    }

    @Test
    @DisplayName("rejects a negative ceiling, which would refuse even an empty tag set")
    void rejects_a_negative_ceiling() {
        assertThrows(IllegalArgumentException.class, () -> new TagLimits(-1));
    }
}
