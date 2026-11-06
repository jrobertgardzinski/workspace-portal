package com.jrobertgardzinski.memes.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.memes.application.ObjectStore;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A meme whose row is there and whose bytes are NOT in the active object store. This is not a
 * hypothetical: switching {@code MEMES_BLOB_STORE} from {@code db} to {@code s3} left 26 of 91
 * memes in exactly this state on the running stack (their images stayed in {@code meme_blobs},
 * which the S3 store never looks at), and an operator deleting an object by hand does the same.
 *
 * <p>What such a meme used to be: a ghost. Every gate in the service asked {@code find()}, which
 * joins the row to its object, so a missing object meant "no such meme" — the gallery listed the
 * tile, and {@code /meta}, {@code /votes}, {@code /thumbnail} and DELETE all answered 404. Neither
 * the author nor a moderator could take it down, because the controller answered 404 before it ever
 * got to the deletion.
 *
 * <p>What it must be instead, and what these tests pin: the ROW decides existence. Everything that
 * is not the picture keeps working — metadata, the tally, moderation and, above all, deletion — and
 * only the picture itself is honestly 404, because there is no picture to serve.
 */
@Epic("Data")
@Feature("Meme without its bytes")
// The upload ceiling is off, and that is deliberate: every test here needs its OWN ghost (three of
// them destroy it), which is five uploads by one user in one minute — half the default quota of a
// user other test classes share this context with. Turning the dial off buys this class its own
// context and leaves everyone else's quota alone; the ceiling itself is pinned by UploadRateLimitTest.
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class},
        properties = "memes.upload.rate-limit-per-minute=0")
@AutoConfigureMockMvc
class MemeWithoutItsBytesTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ObjectStore objects;

    private String memeId;

    @BeforeEach
    void uploadAsAliceThenLoseTheBytes() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "meme.bmp", "image/bmp", bmp());
        String body = mockMvc.perform(multipart("/memes").file(file)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        memeId = objectMapper.readTree(body).get("id").asText();

        // the bytes leave the ACTIVE store while the row stays — the store switch, reproduced
        objects.delete(memeId);
    }

    @Test
    @DisplayName("the gallery lists it and its metadata answers — the row is what exists")
    void the_row_still_answers_for_itself() throws Exception {
        String gallery = mockMvc.perform(get("/memes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(gallery.contains(memeId), "the wall renders a tile for it, so the rest must not deny it exists");

        mockMvc.perform(get("/memes/{id}/meta", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.own").value(true));      // and the author is offered Delete

        mockMvc.perform(get("/memes/{id}/votes", memeId))
                .andExpect(status().isOk());                    // the public tally is about the row
    }

    @Test
    @DisplayName("the picture itself is 404 — there is no picture, and that is the honest answer")
    void the_image_is_the_only_404() throws Exception {
        mockMvc.perform(get("/memes/{id}", memeId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/memes/{id}/thumbnail", memeId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the author can delete it — the whole point: nobody's content may be undeletable")
    void the_author_can_still_delete_it() throws Exception {
        mockMvc.perform(delete("/memes/{id}", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.by").value("AUTHOR"));

        mockMvc.perform(get("/memes/{id}/meta", memeId)).andExpect(status().isNotFound());
        String gallery = mockMvc.perform(get("/memes")).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(gallery.contains(memeId), "and it is off the wall");
    }

    @Test
    @DisplayName("a moderator can delete it and flag it — moderation reaches every listed meme")
    void a_moderator_can_still_act_on_it() throws Exception {
        mockMvc.perform(put("/memes/{id}/nsfw", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"nsfw\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/memes/{id}", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.by").value("MODERATOR"));
    }

    @Test
    @DisplayName("a stranger is still refused — the fix widens what works, not who may do it")
    void authorisation_is_unchanged() throws Exception {
        mockMvc.perform(delete("/memes/{id}", memeId)
                        .header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/memes/{id}", "no-such-meme-at-all")
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isNotFound());   // a meme that truly does not exist is still 404
    }

    private static byte[] bmp() throws Exception {
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt()); // unique content -> no dedup
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return out.toByteArray();
    }
}
