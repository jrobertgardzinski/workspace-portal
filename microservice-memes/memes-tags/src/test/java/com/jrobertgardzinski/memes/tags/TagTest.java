package com.jrobertgardzinski.memes.tags;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Epic("Tags")
@Feature("Tag value object")
class TagTest {

    @Test
    @DisplayName("raw input is normalised: case and padding do not multiply tags")
    void normalises_case_and_padding() {
        assertEquals(Tag.of("cats"), Tag.of(" Cats "));
        assertEquals("monday-mood", Tag.of("Monday-Mood").value());
    }

    @Test
    @DisplayName("a tag is a search key, not free text — anything else is refused")
    void refuses_illegal_tags() {
        for (String bad : new String[]{null, "", "a", "has space", "łąka", "double--dash",
                "-leading", "trailing-", "x".repeat(31)}) {
            assertThrows(IllegalArgumentException.class, () -> Tag.of(bad), String.valueOf(bad));
        }
    }
}
