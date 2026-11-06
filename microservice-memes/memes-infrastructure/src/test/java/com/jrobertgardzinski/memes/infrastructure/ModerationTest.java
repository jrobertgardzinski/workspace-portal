package com.jrobertgardzinski.memes.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moderation gate on delete: the author removes their own meme, a stranger cannot, and a MODERATOR
 * (its roles come from microservice-security's /me, stubbed here) removes anyone's.
 */
@Epic("Infrastructure")
@Feature("Moderation gate")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class ModerationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("the author deletes their own; a stranger is refused; a moderator deletes anyone's")
    void moderation_delete() throws Exception {
        String own = upload(TestAuthConfig.VALID_TOKEN);

        // a stranger cannot delete alice's meme
        mockMvc.perform(delete("/memes/{id}", own).header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("NOT_YOURS"));

        // the author can
        mockMvc.perform(delete("/memes/{id}", own).header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.by").value("AUTHOR"));
        mockMvc.perform(get("/memes/{id}", own)).andExpect(status().isNotFound());

        // a moderator can delete someone else's (bob's) meme
        String bobs = upload(TestAuthConfig.SECOND_TOKEN);
        mockMvc.perform(delete("/memes/{id}", bobs).header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.by").value("MODERATOR"));
        mockMvc.perform(get("/memes/{id}", bobs)).andExpect(status().isNotFound());

        // deleting without a token is refused (a write needs sign-in)
        mockMvc.perform(delete("/memes/{id}", "ghost")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the meta endpoint tells the caller whether the meme is theirs, so the UI can offer delete")
    void meta_reports_ownership() throws Exception {
        String id = upload(TestAuthConfig.VALID_TOKEN);
        mockMvc.perform(get("/memes/{id}/meta", id)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.own").value(true))
                // masked even for the uploader themselves: the UI reads own, never an address
                // (what the public sees is pinned by MemeMetaPrivacyTest)
                .andExpect(jsonPath("$.author").value("a***@example.com"));
        mockMvc.perform(get("/memes/{id}/meta", "ghost")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a body that states no flag is refused — it does not quietly take the flag back")
    void an_unstated_nsfw_field_is_refused() throws Exception {
        String id = upload(TestAuthConfig.VALID_TOKEN);
        mockMvc.perform(put("/memes/{id}/nsfw", id)
                        .header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN)
                        .contentType("application/json").content("{\"nsfw\":true}"))
                .andExpect(status().isOk());

        // a misspelled key (a UI build, a hand-written curl) states nothing about nsfw at all
        mockMvc.perform(put("/memes/{id}/nsfw", id)
                        .header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN)
                        .contentType("application/json").content("{\"value\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("MISSING_FLAG"));

        mockMvc.perform(get("/memes/{id}/meta", id))
                .andExpect(jsonPath("$.nsfw").value(true));
    }

    private String upload(String token) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, ThreadLocalRandom.current().nextInt(0xFFFFFF));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        String body = mockMvc.perform(multipart("/memes")
                        .file(new MockMultipartFile("file", "m.png", "image/png", png.toByteArray()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
