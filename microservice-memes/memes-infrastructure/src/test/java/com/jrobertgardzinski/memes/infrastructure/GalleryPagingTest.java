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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public gallery listing answers a PAGE, never the whole wall: the endpoint needs no token and
 * nobody is going to render ten thousand tiles, so "everything you have" must not be a request the
 * server honours. The page is also the only bound on how far a caller may reach — an absurd page
 * number is an empty page, not a failure.
 */
@Epic("Infrastructure")
@Feature("Gallery paging")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
// the rate limit is a different guard with its own test; uploading a wall here must not trip it
@TestPropertySource(properties = "memes.upload.rate-limit-per-minute=0")
class GalleryPagingTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void uploadAWall() throws Exception {
        for (int i = 0; i < 5; i++) {
            upload();
        }
    }

    @Test
    @DisplayName("the wall is walked page by page, and pages do not overlap")
    void pages_partition_the_wall() throws Exception {
        List<String> first = idsOf("/memes?page=0&size=2");
        List<String> second = idsOf("/memes?page=1&size=2");

        assertEquals(2, first.size(), "a page holds exactly what was asked for while rows remain");
        assertEquals(2, second.size());
        Set<String> seen = new HashSet<>(first);
        seen.retainAll(second);
        assertTrue(seen.isEmpty(), "the second page must not repeat the first: " + first + " / " + second);
    }

    @Test
    @DisplayName("an absurd page number is an empty page, not a 500")
    void an_out_of_range_page_is_empty() throws Exception {
        // page * size overflows a signed int here; on ints that wraps into a NEGATIVE offset,
        // which the database rejects and the caller sees as a bare server error
        assertEquals(List.of(), idsOf("/memes?page=25000000&size=100"));
    }

    @Test
    @DisplayName("a nonsense page size is clamped, never taken literally")
    void a_nonsense_size_is_clamped() throws Exception {
        // the ceiling above (MAX_PAGE_SIZE) has no cheap assertion in a gallery this small; the
        // floor does, and it is the one that reaches the database as a broken LIMIT
        assertEquals(1, idsOf("/memes?page=0&size=-5").size(), "at least one row, never a negative LIMIT");
    }

    private List<String> idsOf(String url) throws Exception {
        String body = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(body).forEach(entry -> ids.add(entry.get("id").asText()));
        return ids;
    }

    private void upload() throws Exception {
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt()); // unique content -> no dedup
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        mockMvc.perform(multipart("/memes")
                        .file(new MockMultipartFile("file", "meme.bmp", "image/bmp", out.toByteArray()))
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isCreated());
    }
}
