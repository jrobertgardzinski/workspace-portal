package com.jrobertgardzinski.memes.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /memes/scores?ids=...} — the read a wall of tiles uses instead of mining the capped
 * hot ranking for numbers.
 *
 * <p>What this pins is the shape of the ANSWER, because the UI's honesty depends on it: an id that
 * comes back has a real score (0 included, and 0 is then a fact), and an id that does not come back
 * is the answer "no tally" — the wall renders that as "no tally" rather than as zero. Flatten the
 * two on the wire and the client cannot tell them apart, whatever it does with them.
 */
@Epic("Infrastructure")
@Feature("Batch scores")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
// a handful of uploads in one test must not trip the upload rate limit
@TestPropertySource(properties = "memes.upload.rate-limit-per-minute=0")
class MemeScoresBatchTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("a voted meme carries its score; an unvoted one carries a real, spoken-out zero")
    void known_scores_include_zero() throws Exception {
        String voted = upload();
        String quiet = upload();
        vote(voted);

        Map<String, Integer> scores = scoresOf(voted, quiet);

        assertEquals(1, scores.get(voted).intValue(), "the meme somebody up-voted");
        assertEquals(0, scores.get(quiet).intValue(),
                "an existing meme with no ballots IS a zero, and says so");
    }

    @Test
    @DisplayName("an id this service has no meme for is missing from the answer, not reported as 0")
    void an_unknown_id_is_absent() throws Exception {
        String real = upload();
        String neverExisted = UUID.randomUUID().toString();

        Map<String, Integer> scores = scoresOf(real, neverExisted);

        assertEquals(0, scores.get(real).intValue());
        assertFalse(scores.containsKey(neverExisted),
                "a 0 here would tell the UI 'nobody voted on it' about a meme that does not exist — "
                        + "the very confusion the wall's ▲ 0 was making");
    }

    @Test
    @DisplayName("the same meme asked about twice is answered once")
    void duplicates_collapse() throws Exception {
        String id = upload();

        assertEquals(1, scoresOf(id, id).size(), "the answer is keyed by meme, not by mention");
    }

    @Test
    @DisplayName("asking about nothing is an empty answer, not a failure")
    void no_ids_is_an_empty_answer() throws Exception {
        assertEquals(Map.of(), scoresOf());
        assertEquals("[]", body(get("/memes/scores")));   // the parameter left out entirely
    }

    @Test
    @DisplayName("a batch bigger than a page of the wall is refused, not silently truncated")
    void the_batch_has_a_ceiling() throws Exception {
        // truncating would answer with 100 scores to a question about 101 memes — and the missing
        // one would read as "no tally" for a meme that has one. A refusal makes the caller page.
        String ids = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/memes/scores").param("ids", ids))
                .andExpect(status().isBadRequest());
    }

    /** The endpoint's answer as a map — the shape a client keys by id, absences and all. */
    private Map<String, Integer> scoresOf(String... ids) throws Exception {
        var request = get("/memes/scores");
        if (ids.length > 0) {
            request = request.param("ids", String.join(",", ids));
        }
        Map<String, Integer> scores = new HashMap<>();
        objectMapper.readTree(body(request)).forEach(entry ->
                scores.put(entry.get("memeId").asText(), entry.get("score").asInt()));
        return scores;
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void vote(String memeId) throws Exception {
        mockMvc.perform(post("/memes/" + memeId + "/votes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"UP\"}")
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isOk());
    }

    private String upload() throws Exception {
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt());   // unique content -> no dedup
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        String created = mockMvc.perform(multipart("/memes")
                        .file(new MockMultipartFile("file", "meme.bmp", "image/bmp", out.toByteArray()))
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }
}
