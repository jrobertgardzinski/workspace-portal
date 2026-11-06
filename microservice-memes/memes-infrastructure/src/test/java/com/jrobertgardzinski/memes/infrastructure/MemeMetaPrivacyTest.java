package com.jrobertgardzinski.memes.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@code /memes/{id}/meta} may tell the world. The gallery is public, so this endpoint answers
 * anonymous callers — and the only question the UI asks it is "may I offer a Delete button here?".
 * That question is answered by a boolean derived from the caller's own token; the uploader's
 * address is never part of the answer, masked or not, because a public listing of ids plus one
 * cheap call per id is otherwise a complete address book of everyone who ever uploaded.
 */
@Epic("Infrastructure")
@Feature("Meme metadata privacy")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class MemeMetaPrivacyTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String memeId;

    @BeforeEach
    void uploadAsAlice() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "meme.bmp", "image/bmp", bmp());
        String body = mockMvc.perform(multipart("/memes").file(file)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        memeId = objectMapper.readTree(body).get("id").asText();
    }

    @Test
    @DisplayName("an anonymous caller gets a masked uploader and own=false — never the address")
    void anonymous_meta_carries_no_address() throws Exception {
        String body = mockMvc.perform(get("/memes/{id}/meta", memeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.author").value("a***@example.com"))
                .andExpect(jsonPath("$.own").value(false))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(TestAuthConfig.SIGNED_IN_USER),
                "the uploader's e-mail must not appear anywhere in a public response: " + body);
    }

    @Test
    @DisplayName("the uploader is told the meme is theirs — that is what the Delete button needs")
    void the_uploader_gets_own_true() throws Exception {
        String body = mockMvc.perform(get("/memes/{id}/meta", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.own").value(true))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(TestAuthConfig.SIGNED_IN_USER),
                "not even to the uploader: the field the UI reads is own, not the address");
    }

    @Test
    @DisplayName("another signed-in user is not the owner, and learns nothing about who is")
    void a_stranger_gets_own_false() throws Exception {
        String body = mockMvc.perform(get("/memes/{id}/meta", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.own").value(false))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(TestAuthConfig.SIGNED_IN_USER), body);
    }

    @Test
    @DisplayName("deletion stays server-authorised: own=false does not stop a moderator")
    void authorisation_still_lives_on_the_server() throws Exception {
        // the masking changes what the UI is TOLD, never who may act — a stranger is refused and
        // a moderator is not, exactly as before
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/memes/{id}", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/memes/{id}", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN))
                .andExpect(status().isOk());
    }

    private static byte[] bmp() throws Exception {
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt()); // unique content -> no dedup
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return out.toByteArray();
    }
}
