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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP glue for {@code vote-meme.feature}: distinct signed-in users (the stub gate knows two) vote
 * on freshly uploaded memes; one vote per user, so re-voting never stacks. Uploads use random
 * pixels — distinct content keeps deduplication from linking scenarios together.
 */
public class VoteSteps {

    private static final List<String> USER_TOKENS =
            List.of(TestAuthConfig.VALID_TOKEN, TestAuthConfig.SECOND_TOKEN);

    @LocalServerPort
    int port;

    private String memeA;
    private String memeB;
    private int lastReportedScore;

    @Given("two uploaded MEMES A and B")
    public void twoUploadedMemes() throws Exception {
        memeA = upload();
        memeB = upload();
    }

    @When("^(\\d+) USERS? up-votes? MEME (\\w+)$")
    public void usersUpVoteMeme(int count, String which) {
        for (int i = 0; i < count; i++) {
            vote(idOf(which), USER_TOKENS.get(i));
        }
    }

    @When("the USER up-votes MEME {word} {int} times")
    public void theUserUpVotesRepeatedly(String which, int times) {
        for (int i = 0; i < times; i++) {
            vote(idOf(which), TestAuthConfig.VALID_TOKEN);
        }
    }

    @When("a GUEST tries to up-vote MEME {word}")
    public void anAnonymousUserTriesToUpVote(String which) {
        AuthSteps.lastAnonymousAttempt = RestAssured.given().port(port)
                .contentType("application/json")
                .body("{\"direction\":\"UP\"}")
                .post("/memes/" + idOf(which) + "/votes");
    }

    @Then("MEME {word} ranks above MEME {word} in the hot list")
    public void ranksAbove(String higher, String lower) {
        Response hot = RestAssured.given().port(port).get("/memes/hot");
        hot.then().statusCode(200);
        List<String> order = hot.jsonPath().getList("memeId");
        assertTrue(order.indexOf(idOf(higher)) < order.indexOf(idOf(lower)),
                "expected " + higher + " to rank above " + lower + ", order was " + order);
    }

    @Then("MEME {word}'s score is {int}")
    public void memeScoreIs(String which, int score) {
        assertEquals(score, lastReportedScore, "score reported by the last vote");
    }

    @Then("MEME {word}'s score is {int} and the USER's vote is gone")
    public void memeScoreIsAndVoteGone(String which, int score) {
        assertEquals(score, lastReportedScore, "score reported by the last vote");
        Response tally = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .get("/memes/" + idOf(which) + "/votes");
        tally.then().statusCode(200);
        assertEquals(null, tally.jsonPath().getString("myVote"), "expected the vote to be retracted");
    }

    private void vote(String memeId, String token) {
        Response response = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"direction\":\"UP\"}")
                .post("/memes/" + memeId + "/votes");
        response.then().statusCode(200);
        lastReportedScore = response.jsonPath().getInt("score");
    }

    private String idOf(String which) {
        return which.equals("A") ? memeA : memeB;
    }

    private String upload() throws Exception {
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, ThreadLocalRandom.current().nextInt()); // unique content -> no dedup
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .multiPart("file", "meme.bmp", out.toByteArray(), "image/bmp")
                .post("/memes")
                .jsonPath().getString("id");
    }
}
