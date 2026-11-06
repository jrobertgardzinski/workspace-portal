package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The upload ceiling: with the limit set to one per minute, a second upload from the same user is
 * refused with 429 — uploads are heavier than reads, so one account cannot flood the gallery.
 */
@Epic("Infrastructure")
@Feature("Upload guards")
@Story("Rate limit")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = "memes.upload.rate-limit-per-minute=1")
class UploadRateLimitTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("a second upload in the window is refused with 429")
    void second_upload_is_rate_limited() throws Exception {
        mockMvc.perform(multipart("/memes").file(image())
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/memes").file(image())
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("RATE_LIMITED"));
    }

    private MockMultipartFile image() throws Exception {
        // distinct pixels each time so dedup never merges the two uploads
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, ThreadLocalRandom.current().nextInt(0xFFFFFF));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        return new MockMultipartFile("file", "m.png", "image/png", png.toByteArray());
    }
}
