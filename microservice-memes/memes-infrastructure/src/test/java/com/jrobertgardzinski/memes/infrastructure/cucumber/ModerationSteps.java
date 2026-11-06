package com.jrobertgardzinski.memes.infrastructure.cucumber;

import com.jrobertgardzinski.memes.application.ObjectStore;
import com.jrobertgardzinski.memes.infrastructure.TestAuthConfig;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP glue for {@code moderate-meme.feature}: the author (alice) uploads; a stranger (bob), a
 * moderator (mod) and an anonymous caller each try to delete. Deletion authority comes from the
 * roles microservice-security reports, stubbed by {@link TestAuthConfig}. The "not a byte remains"
 * step peeks into the {@link ObjectStore} on top of the observable 404s — over HTTP a 404 only
 * proves the meme's ROW is gone, not that no orphaned bytes linger in storage; the glue already
 * reaches into the application for such truths (see {@code AdminPolicySteps}), the scenarios stay
 * business-worded.
 */
public class ModerationSteps {

    @LocalServerPort
    int port;

    @Autowired
    ObjectStore objects;

    private String memeId;
    private Response lastDelete;
    private Response lastFlag;

    @Given("a MEME uploaded by its author")
    public void aMemeUploadedByItsAuthor() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 2, ThreadLocalRandom.current().nextInt(0xFFFFFF));   // unique, so dedup never links memes
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        memeId = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .multiPart("file", "meme.png", png.toByteArray(), "image/png")
                .post("/memes")
                .jsonPath().getString("id");
    }

    @When("another USER tries to delete it")
    public void anotherUserDeletes() {
        lastDelete = delete(TestAuthConfig.SECOND_TOKEN);
    }

    @When("the author deletes it")
    public void theAuthorDeletes() {
        lastDelete = delete(TestAuthConfig.VALID_TOKEN);
    }

    @When("a MODERATOR deletes it")
    public void aModeratorDeletes() {
        lastDelete = delete(TestAuthConfig.MODERATOR_TOKEN);
    }

    @When("a GUEST tries to delete it")
    public void anAnonymousUserDeletes() {
        lastDelete = RestAssured.given().port(port).delete("/memes/{id}", memeId);
    }

    @Then("the deletion is refused as not-theirs")
    public void refusedAsNotTheirs() {
        assertEquals(403, lastDelete.statusCode());
        assertEquals("NOT_YOURS", lastDelete.jsonPath().getString("status"));
    }

    @Then("the deletion is refused as sign-in required")
    public void refusedAsSignInRequired() {
        assertEquals(401, lastDelete.statusCode());
    }

    @Then("the deletion succeeds as the author")
    public void succeedsAsAuthor() {
        assertEquals(200, lastDelete.statusCode());
        assertEquals("AUTHOR", lastDelete.jsonPath().getString("by"));
    }

    @Then("the deletion succeeds as a MODERATOR")
    public void succeedsAsModerator() {
        assertEquals(200, lastDelete.statusCode());
        assertEquals("MODERATOR", lastDelete.jsonPath().getString("by"));
    }

    @Then("the MEME can still be fetched")
    public void memeStillThere() {
        assertEquals(200, RestAssured.given().port(port).get("/memes/{id}", memeId).statusCode());
    }

    @Then("the MEME is gone")
    public void memeGone() {
        assertEquals(404, RestAssured.given().port(port).get("/memes/{id}", memeId).statusCode());
    }

    @Given("a browser has already been served its WebP variant")
    public void aBrowserHasBeenServedItsWebpVariant() {
        Response webp = RestAssured.given().port(port)
                .header("Accept", "image/webp,image/*")
                .get("/memes/{id}", memeId);
        assertEquals(200, webp.statusCode());
        assertEquals("image/webp", webp.contentType(),
                "the WebP variant really was minted (and thereby cached) before the deletion");
    }

    @Then("not a byte of the MEME remains, in any form")
    public void notAByteRemains() {
        // observable over HTTP: both representations answer gone
        assertEquals(404, RestAssured.given().port(port).get("/memes/{id}", memeId).statusCode(),
                "the original is gone");
        assertEquals(404, RestAssured.given().port(port)
                        .header("Accept", "image/webp,image/*")
                        .get("/memes/{id}", memeId).statusCode(),
                "asking for WebP finds nothing either");
        // and behind the curtain: the storage holds neither the original nor the WebP variant —
        // a 404 alone would also be true of orphaned bytes whose row was deleted
        assertTrue(objects.get(memeId).isEmpty(), "no original bytes linger in storage");
        assertTrue(objects.get(memeId + ".webp").isEmpty(), "no WebP variant lingers in storage");
    }

    @When("a MODERATOR flags it NSFW")
    public void moderatorFlagsNsfw() {
        lastFlag = flag(TestAuthConfig.MODERATOR_TOKEN, true);
        assertEquals(200, lastFlag.statusCode());
    }

    @When("a MODERATOR takes the NSFW flag back")
    public void moderatorUnflags() {
        lastFlag = flag(TestAuthConfig.MODERATOR_TOKEN, false);
        assertEquals(200, lastFlag.statusCode());
    }

    @When("the author tries to flag it NSFW")
    public void authorTriesToFlag() {
        lastFlag = flag(TestAuthConfig.VALID_TOKEN, true);
    }

    @Then("the flagging is refused as not-a-MODERATOR")
    public void flaggingRefused() {
        assertEquals(403, lastFlag.statusCode());
        assertEquals("NOT_A_MODERATOR", lastFlag.jsonPath().getString("status"));
    }

    @Then("the gallery lists the MEME as NSFW")
    public void listedAsNsfw() {
        assertEquals(true, galleryNsfwOf(memeId), "the listing must carry the flag");
    }

    @Then("the gallery lists the MEME as safe")
    public void listedAsSafe() {
        assertEquals(false, galleryNsfwOf(memeId));
    }

    private Boolean galleryNsfwOf(String id) {
        return RestAssured.given().port(port).get("/memes")
                .jsonPath().getBoolean("find { it.id == '" + id + "' }.nsfw");
    }

    private Response flag(String token, boolean nsfw) {
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"nsfw\":" + nsfw + "}")
                .put("/memes/{id}/nsfw", memeId);
    }

    private Response delete(String token) {
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .delete("/memes/{id}", memeId);
    }
}
