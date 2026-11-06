package com.jrobertgardzinski.memes.infrastructure.cucumber;

import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.application.ObjectStore;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.system.RestoreUserContent;
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
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Glue for {@code account-erasure.feature}. The saga's three commands are driven through their use
 * cases rather than through a broker — the same choice every other participant test in this
 * workspace makes, because the contract with the orchestrator is the message SHAPE (pinned by the
 * pacts) and not the transport.
 *
 * <p>Visibility is asked over real HTTP, because "gone from the gallery" is a promise made to a
 * reader, not to a repository. The pivot is asked of the {@link ObjectStore}: whether the image is
 * still there is the one fact that separates a mark from an erasure.
 */
public class ErasureSagaSteps {

    @LocalServerPort
    int port;

    @Autowired
    MarkUserContentForErasure markForErasure;

    @Autowired
    RestoreUserContent restoreUserContent;

    @Autowired
    PurgeUserContent purgeUserContent;

    @Autowired
    ObjectStore objectStore;

    private String memeId;
    private Response lastTakedown;

    @Given("a leaver with one MEME in the gallery")
    public void aLeaverWithOneMeme() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 3, ThreadLocalRandom.current().nextInt(0xFFFFFF));   // unique: no dedup
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        memeId = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN)
                .multiPart("file", "leaving.png", png.toByteArray(), "image/png")
                .post("/memes")
                .jsonPath().getString("id");
        assertEquals(200, RestAssured.given().port(port).get("/memes/" + memeId).statusCode(),
                "the scenario starts from a meme the world can see");
    }

    @When("the ORCHESTRATOR commands the PURGE of their content")
    public void theOrchestratorCommandsThePurge() {
        // PURGE_USER_CONTENT: the reversible half. Written twice by one scenario on purpose —
        // at-least-once delivery means this really does arrive twice
        markForErasure.execute(TestAuthConfig.SECOND_USER_ID);
    }

    @When("the comments service never confirms and the ORCHESTRATOR compensates")
    public void theOrchestratorCompensates() {
        // RESTORE_USER_CONTENT: what the orchestrator sends when it gives up on a sibling
        // participant. This service is not the one that failed and does not get to decide —
        // it only obeys
        restoreUserContent.execute(TestAuthConfig.SECOND_USER_ID);
    }

    @When("every participant confirms and the ORCHESTRATOR closes the SAGA")
    public void theOrchestratorClosesTheSaga() {
        // ERASE_USER_CONTENT: the closure, and the only command that destroys anything
        purgeUserContent.execute(TestAuthConfig.SECOND_USER_ID, Optional.empty());
    }

    @When("the AUTHOR tries to delete their own MEME")
    public void theAuthorTriesToDeleteTheirMeme() {
        // the leaver themself, holding the very token the meme was uploaded with
        lastTakedown = takedown(TestAuthConfig.SECOND_TOKEN);
    }

    @When("a MODERATOR tries to delete the MEME")
    public void aModeratorTriesToDeleteTheMeme() {
        // and the one caller who may delete anybody's meme: the reservation outranks the role
        lastTakedown = takedown(TestAuthConfig.MODERATOR_TOKEN);
    }

    @Then("the takedown finds nothing")
    public void theTakedownFindsNothing() {
        // 404 and nothing else: the delete endpoint asks the gallery for the meme first, and a
        // reserved meme is not in it — so the refusal is "no such meme" for the author and for a
        // moderator alike, rather than a permission verdict. Nothing downstream of that check
        // runs: no ballots purged, no announcement, no row deleted
        assertEquals(404, lastTakedown.statusCode(),
                "a meme the saga has reserved was taken down anyway");
    }

    @Then("the MEME is gone from the gallery")
    public void theMemeIsGoneFromTheGallery() {
        assertEquals(404, RestAssured.given().port(port).get("/memes/" + memeId).statusCode(),
                "the picture still answers");
        assertEquals(404, RestAssured.given().port(port).get("/memes/" + memeId + "/meta").statusCode(),
                "the metadata still answers — hiding the picture alone is not hiding the meme");
    }

    @Then("the MEME is back in the gallery")
    public void theMemeIsBackInTheGallery() {
        assertEquals(200, RestAssured.given().port(port).get("/memes/" + memeId).statusCode());
        assertEquals(200, RestAssured.given().port(port).get("/memes/" + memeId + "/meta").statusCode());
    }

    // ONE annotation, although the feature says both "But the meme is still stored" and "And the
    // meme is still stored": Given/When/Then/And/But are interchangeable keywords to cucumber, so
    // a second annotation with the same text is a DUPLICATE definition — and a duplicate does not
    // fail this scenario alone, it fails the whole glue, taking every other feature in the module
    // with it
    @Then("the MEME is still stored")
    public void theMemeIsStillStored() {
        // the whole difference between a mark and an erasure, in one assertion: the picture is
        // invisible AND intact, which is what gives the orchestrator something to undo
        assertTrue(objectStore.get(memeId).isPresent(),
                "the mark must not have touched the image in object storage");
    }

    @Then("the image itself is gone for good")
    public void theImageIsGoneFromObjectStorage() {
        // THE PIVOT. Everything before this line was undoable; nothing after it is
        assertTrue(objectStore.get(memeId).isEmpty(),
                "the closure must take the image out of object storage, not just the row");
    }

    private Response takedown(String token) {
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .delete("/memes/{id}", memeId);
    }

    @Then("a late compensation brings nothing back")
    public void aLateCompensationBringsNothingBack() {
        // the orchestrator never sends this after a closure — the state machine forbids it — but
        // if one did arrive, past the pivot there is nothing to restore and nothing to throw
        restoreUserContent.execute(TestAuthConfig.SECOND_USER_ID);
        assertEquals(404, RestAssured.given().port(port).get("/memes/" + memeId).statusCode());
        assertTrue(objectStore.get(memeId).isEmpty());
    }
}
