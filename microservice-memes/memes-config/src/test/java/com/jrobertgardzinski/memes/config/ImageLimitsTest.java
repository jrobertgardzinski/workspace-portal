package com.jrobertgardzinski.memes.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Epic("Config")
@Feature("Image limits")
class ImageLimitsTest {

    @Test
    @DisplayName("keeps a positive maximum dimension")
    void keeps_a_positive_maximum() {
        assertEquals(1024, new ImageLimits(1024).maxDimension());
    }

    @Test
    @DisplayName("rejects a non-positive maximum dimension")
    void rejects_non_positive() {
        assertThrows(IllegalArgumentException.class, () -> new ImageLimits(0));
        assertThrows(IllegalArgumentException.class, () -> new ImageLimits(-1));
    }
}
