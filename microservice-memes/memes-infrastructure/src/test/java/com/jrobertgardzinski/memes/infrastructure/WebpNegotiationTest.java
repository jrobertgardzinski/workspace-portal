package com.jrobertgardzinski.memes.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.memes.application.ImageEncoder;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Content negotiation on the image: a client that accepts WebP gets WebP (encoded once, then
 * served from the cache); a client that does not still gets the stored PNG.
 */
@Epic("Infrastructure")
@Feature("WebP negotiation")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class, WebpNegotiationTest.FakeEncoder.class})
@AutoConfigureMockMvc
class WebpNegotiationTest {

    static final byte[] FAKE_WEBP = "RIFF....WEBPVP8 ".getBytes();
    static final AtomicInteger encodeCalls = new AtomicInteger();

    @TestConfiguration
    static class FakeEncoder {
        @Bean
        @Primary
        ImageEncoder fakeImageEncoder() {
            return png -> {
                encodeCalls.incrementAndGet();
                return Optional.of(FAKE_WEBP);
            };
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("Accept: image/webp is served WebP and encoded only once")
    void negotiates_and_caches_webp() throws Exception {
        encodeCalls.set(0);
        String id = objectMapper.readTree(mockMvc.perform(multipart("/memes").file(png())
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // no webp in Accept -> PNG
        byte[] plain = mockMvc.perform(get("/memes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals((byte) 0x89, plain[0], "PNG magic");

        // webp in Accept -> WebP, encoded
        byte[] webp = mockMvc.perform(get("/memes/{id}", id).header(ACCEPT, "image/webp,image/*"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(FAKE_WEBP, webp);

        // second webp request -> from cache, no second encode
        mockMvc.perform(get("/memes/{id}", id).header(ACCEPT, "image/webp"))
                .andExpect(header().string("Content-Type", "image/webp"));
        assertEquals(1, encodeCalls.get(), "encoded once, then served from the cache");
    }

    private MockMultipartFile png() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("file", "m.png", "image/png", out.toByteArray());
    }
}
