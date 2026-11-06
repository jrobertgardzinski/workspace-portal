package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.identity.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Black-box web test: upload a BMP (signed in via the stubbed gate), then fetch the meme back
 * anonymously and check it was served as PNG.
 */
@Epic("Infrastructure")
@Feature("Upload and serve")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class MemeControllerTest {

    private static final UserId SOMEBODY = UserId.random();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    com.jrobertgardzinski.memes.domain.MemeRepository memes;

    @Test
    void uploads_and_serves_an_optimized_meme() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "meme.bmp", "image/bmp", bmp());

        String body = mockMvc.perform(multipart("/memes").file(file)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        byte[] png = mockMvc.perform(get("/memes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        assertEquals((byte) 0x89, png[0]); // PNG magic
    }

    @Test
    void an_uploaded_transparent_png_is_STORED_with_its_alpha_channel() throws Exception {
        // The whole chain, not just the optimizer: the upload is re-encoded and the SERVICE KEEPS
        // THE RESULT, so this is the only place that can prove the user's transparency survived
        // storage. 1600 px on the long side is deliberately above memes.image.max-dimension (1024),
        // because the loss happened in the scaler — an image below the limit was never touched.
        MockMultipartFile sticker = new MockMultipartFile("file", "sticker.png", "image/png",
                transparentQuadrantPng(1600, 1200));

        String body = mockMvc.perform(multipart("/memes").file(sticker)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        byte[] served = mockMvc.perform(get("/memes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        BufferedImage stored = ImageIO.read(new java.io.ByteArrayInputStream(served));
        assertEquals(1024, stored.getWidth(), "it really was scaled down on the way in");
        assertEquals(0, stored.getRGB(2, 2) >>> 24,
                "the transparent corner survived the upload — this is the byte-loss the store cannot undo");
    }

    /** Opaque red with a fully transparent top-left quadrant — see WebImageOptimizerTest. */
    private static byte[] transparentQuadrantPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, x < width / 2 && y < height / 2 ? 0x00000000 : 0xffff0000);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void refuses_a_non_image_upload_with_400_not_500() throws Exception {
        MockMultipartFile garbage = new MockMultipartFile("file", "not-an-image.pdf",
                "application/pdf", "%PDF-1.4 definitely not pixels".getBytes());

        mockMvc.perform(multipart("/memes").file(garbage)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.detail").exists());
    }

    @Test
    void refuses_a_truncated_image_upload_with_400_not_500() throws Exception {
        // a real PNG cut short after its header — dies mid-parse (InvalidImageException), which
        // is still the caller's broken upload, not a server error
        byte[] truncated = java.util.Arrays.copyOf(pngBytes(), 24);
        MockMultipartFile broken = new MockMultipartFile("file", "broken.png", "image/png", truncated);

        mockMvc.perform(multipart("/memes").file(broken)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.detail").exists());
    }

    @Test
    void corrupted_stored_bytes_surface_as_a_500_not_a_400() throws Exception {
        // uploads are validated by the optimizer, so bytes that no longer decode AFTER storage
        // are the server's data gone bad — the thumbnail must answer 500 (our fault, generic
        // body), never the 400 the same decoder failure earns a broken UPLOAD
        memes.save(new com.jrobertgardzinski.memes.domain.Meme(
                "rotten", SOMEBODY, "png", "these were pixels once".getBytes()));

        mockMvc.perform(get("/memes/{id}/thumbnail", "rotten"))
                .andExpect(status().isInternalServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.detail").value("internal error"));
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] bmp() throws Exception {
        BufferedImage image = new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return out.toByteArray();
    }
}
