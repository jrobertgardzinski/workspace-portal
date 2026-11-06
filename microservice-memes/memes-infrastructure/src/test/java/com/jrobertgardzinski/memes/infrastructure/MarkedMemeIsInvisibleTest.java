package com.jrobertgardzinski.memes.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dirty-read test, over the REAL web boundary: once an account-deletion saga has marked a
 * leaver's memes, <strong>no public endpoint of this service may return them or admit they
 * exist</strong> — and every one of those endpoints is asked here, not just the obvious one.
 *
 * <p>The list is the interesting part, because each entry is a path that reaches the database by a
 * different route and could have been forgotten separately: the gallery page, the tag search, the
 * hot ranking (which reads votes and joins the memes), the batch scores, the metadata, the image
 * itself, the thumbnail, the meme's own tag list, and the dedup index — the last of which is not a
 * read of a meme at all until you notice that re-uploading a picture and being handed somebody's
 * id is a way to prove they posted it. {@code MemeReadFilterTest} is the static half of the same promise; this is the
 * behavioural half, and it is the one that would catch a filter defeated by a view definition.
 *
 * <p>The last test is the compensation: the same endpoints, after the orchestrator changed its
 * mind, must answer exactly as they did before the mark. A hiding that cannot be undone is not the
 * feature this whole design was for.
 */
@Epic("Saga")
@Feature("Marked meme is invisible")
@Story("Every public read")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = "memes.upload.rate-limit-per-minute=0")
class MarkedMemeIsInvisibleTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MarkUserContentForErasure markForErasure;

    @Autowired
    RestoreUserContent restoreUserContent;

    private byte[] picture;
    private String memeId;

    @BeforeEach
    void aliceUploadsTagsAndIsVotedOn() throws Exception {
        picture = uniquePicture();
        memeId = upload(picture);
        mockMvc.perform(post("/memes/" + memeId + "/tags")
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"tags\":[\"cats\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/memes/" + memeId + "/votes")
                        .header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN)
                        .contentType("application/json")
                        .content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a marked meme is gone from every public read at once")
    void nothing_public_returns_a_marked_meme() throws Exception {
        assertTrue(galleryIds().contains(memeId), "the fixture must start from a visible meme");

        markForErasure.execute(TestAuthConfig.SIGNED_IN_USER_ID);

        assertFalse(galleryIds().contains(memeId), "the gallery page");
        assertFalse(ids("/memes?tag=cats").contains(memeId), "the tag search");
        assertFalse(hotIds().contains(memeId), "the hot ranking");
        assertFalse(scoredIds().contains(memeId), "the batch scores");
        mockMvc.perform(get("/memes/" + memeId + "/meta")).andExpect(status().isNotFound());
        mockMvc.perform(get("/memes/" + memeId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/memes/" + memeId + "/thumbnail")).andExpect(status().isNotFound());
        // the one read that answered from meme_tags alone: it handed the leaver's tags to anybody,
        // and a non-empty list where an unknown id returns [] told the caller the meme is real
        assertEquals(List.of(), strings("/memes/" + memeId + "/tags"), "the meme's own tag list");
    }

    @Test
    @DisplayName("re-uploading the marked picture yields a NEW meme, never the leaver's id")
    void the_dedup_index_does_not_hand_out_a_marked_meme() throws Exception {
        markForErasure.execute(TestAuthConfig.SIGNED_IN_USER_ID);

        // bob uploads the very same bytes. The content index still held alice's claim on that
        // hash, so without a status-aware claim this answered with HER meme id — a 200 pointing at
        // content the gallery refuses to show, and a way to prove by experiment that the account
        // being deleted had posted this picture
        String bobs = upload(picture, TestAuthConfig.SECOND_TOKEN);

        assertNotEquals(memeId, bobs, "the dedup index must not hand out a meme under erasure");
        assertTrue(galleryIds().contains(bobs), "and bob's upload is a normal, visible meme");
    }

    @Test
    @DisplayName("the mark takes nothing away but the visibility — tags and votes survive it")
    void the_mark_destroys_nothing() throws Exception {
        markForErasure.execute(TestAuthConfig.SIGNED_IN_USER_ID);
        restoreUserContent.execute(TestAuthConfig.SIGNED_IN_USER_ID);

        assertEquals(List.of("cats"), strings("/memes/" + memeId + "/tags"),
                "the tags were never touched: the mark is a status, not a teardown");
    }

    @Test
    @DisplayName("the compensation gives the gallery back exactly what the mark took")
    void restoring_makes_everything_visible_again() throws Exception {
        markForErasure.execute(TestAuthConfig.SIGNED_IN_USER_ID);

        restoreUserContent.execute(TestAuthConfig.SIGNED_IN_USER_ID);

        assertTrue(galleryIds().contains(memeId), "the gallery page");
        assertTrue(ids("/memes?tag=cats").contains(memeId), "the tag search — its tags survived");
        assertTrue(hotIds().contains(memeId), "the hot ranking — so did the vote");
        assertTrue(scoredIds().contains(memeId), "the batch scores");
        mockMvc.perform(get("/memes/" + memeId + "/meta")).andExpect(status().isOk());
        mockMvc.perform(get("/memes/" + memeId)).andExpect(status().isOk());
    }

    private List<String> galleryIds() throws Exception {
        return ids("/memes?page=0&size=100");
    }

    private List<String> ids(String url) throws Exception {
        return field(url, "id");
    }

    private List<String> hotIds() throws Exception {
        return field("/memes/hot", "memeId");
    }

    private List<String> scoredIds() throws Exception {
        return field("/memes/scores?ids=" + memeId, "memeId");
    }

    private List<String> field(String url, String name) throws Exception {
        String body = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> values = new ArrayList<>();
        objectMapper.readTree(body).forEach(entry -> values.add(entry.get(name).asText()));
        return values;
    }

    private String upload(byte[] image) throws Exception {
        return upload(image, TestAuthConfig.VALID_TOKEN);
    }

    private String upload(byte[] image, String token) throws Exception {
        String body = mockMvc.perform(multipart("/memes")
                        .file(new MockMultipartFile("file", "meme.bmp", "image/bmp", image))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /** A picture no other test in this run can collide with — dedup is content-addressed. */
    private static byte[] uniquePicture() throws Exception {
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, java.util.concurrent.ThreadLocalRandom.current().nextInt());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return out.toByteArray();
    }

    /** The tags endpoint answers a bare JSON array of strings, not objects. */
    private List<String> strings(String url) throws Exception {
        String body = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> values = new ArrayList<>();
        objectMapper.readTree(body).forEach(entry -> values.add(entry.asText()));
        return values;
    }
}
