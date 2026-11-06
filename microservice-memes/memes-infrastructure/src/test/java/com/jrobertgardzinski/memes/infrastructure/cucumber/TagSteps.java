package com.jrobertgardzinski.memes.infrastructure.cucumber;

import com.jrobertgardzinski.memes.infrastructure.TestAuthConfig;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP glue for {@code tag-meme.feature}: the author (alice's token) uploads and tags; searching
 * needs no token — the gallery is public. Uploads use random pixels so deduplication never links
 * scenarios together.
 */
public class TagSteps {

    @LocalServerPort
    int port;

    private String memeId;
    private Response lastTagging;

    @Given("an uploaded MEME")
    public void anUploadedMeme() throws Exception {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 3, ThreadLocalRandom.current().nextInt(0xFFFFFF));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        memeId = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .multiPart("file", "meme.png", png.toByteArray(), "image/png")
                .post("/memes")
                .jsonPath().getString("id");
    }

    @When("the author tags it with {string} and {string}")
    public void theAuthorTags(String first, String second) {
        lastTagging = tag(TestAuthConfig.VALID_TOKEN, first, second);
    }

    @When("another USER tries to tag it with {string}")
    public void anotherUserTags(String tag) {
        lastTagging = tag(TestAuthConfig.SECOND_TOKEN, tag);
    }

    @Then("the gallery filtered by {string} contains that MEME")
    public void galleryContains(String tag) {
        assertEquals(200, lastTagging.statusCode());
        assertTrue(galleryIds(tag).contains(memeId));
    }

    @Then("the gallery filtered by {string} does not")
    public void galleryDoesNot(String tag) {
        assertFalse(galleryIds(tag).contains(memeId));
    }

    @Then("the tagging is refused as not-the-author")
    public void refusedAsNotTheAuthor() {
        assertEquals(403, lastTagging.statusCode());
        assertEquals("NOT_THE_AUTHOR", lastTagging.jsonPath().getString("status"));
    }

    @Then("the tagging is refused as an invalid TAG")
    public void refusedAsInvalidTag() {
        assertEquals(400, lastTagging.statusCode());
        assertEquals("INVALID_TAG", lastTagging.jsonPath().getString("status"));
    }

    private Response tag(String token, String... tags) {
        String body = "{\"tags\":[" + String.join(",",
                List.of(tags).stream().map(t -> "\"" + t + "\"").toList()) + "]}";
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(body)
                .post("/memes/{memeId}/tags", memeId);
    }

    private List<String> galleryIds(String tag) {
        return RestAssured.given().port(port)
                .queryParam("tag", tag)
                .get("/memes")
                .jsonPath().getList("id", String.class);
    }
}
