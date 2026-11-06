package com.jrobertgardzinski.memes.infrastructure.cucumber;

import com.jrobertgardzinski.memes.system.MarkUserContentForErasure;
import com.jrobertgardzinski.memes.domain.PurgePolicyOverride;
import com.jrobertgardzinski.memes.system.PurgeUserContent;
import com.jrobertgardzinski.memes.infrastructure.TestAuthConfig;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP glue for {@code admin-purge-policy.feature}: the admin dials the runtime purge override
 * over REST, a plain user is refused, and the purge use case (driven directly, as everywhere in
 * these tests — the broker is not the contract) obeys the dialled rule. The scenarios share one
 * Spring context, so each starts from a clean dial.
 */
public class AdminPolicySteps {

    @LocalServerPort
    int port;

    @Autowired
    PurgeUserContent purgeUserContent;

    @Autowired
    MarkUserContentForErasure markForErasure;

    @Autowired
    PurgePolicyOverride purgePolicyOverride;

    private String memeId;
    private Response lastChange;

    @Before
    public void cleanDial() {
        purgePolicyOverride.clear("test-setup@example.com");
    }

    @Given("the ADMIN sets the memes purge policy to {string}")
    public void adminSetsPolicy(String rule) {
        lastChange = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.ADMIN_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"memes\":\"" + rule + "\"}")
                .put("/admin/purge-policy");
        assertEquals(200, lastChange.statusCode());
    }

    @When("a USER tries to set the memes purge policy")
    public void plainUserTriesToSetPolicy() {
        lastChange = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .contentType(ContentType.JSON)
                .body("{\"memes\":\"DELETE\"}")
                .put("/admin/purge-policy");
    }

    @When("the ADMIN clears the memes purge-policy override")
    public void adminClearsOverride() {
        lastChange = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.ADMIN_TOKEN)
                .delete("/admin/purge-policy");
        assertEquals(200, lastChange.statusCode());
    }

    @When("a leaver with one MEME is purged without a stated rule")
    public void leaverIsPurged() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 3, ThreadLocalRandom.current().nextInt(0xFFFFFF));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        memeId = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN)
                .multiPart("file", "leaving.png", png.toByteArray(), "image/png")
                .post("/memes")
                .jsonPath().getString("id");
        // both halves of the saga: the closure only ever erases what the MARK reserved, so a
        // purge driven without one would find nothing and this scenario would pass by accident
        markForErasure.execute(TestAuthConfig.SECOND_USER_ID);
        purgeUserContent.execute(TestAuthConfig.SECOND_USER_ID, Optional.empty());
    }

    @Then("the leaver's MEME survives anonymised")
    public void memeSurvives() {
        assertEquals(200, RestAssured.given().port(port).get("/memes/" + memeId).statusCode());
    }

    @Then("the effective memes purge policy is {string}, set by the ADMIN")
    public void effectivePolicySetByAdmin(String rule) {
        // the ADMIN's runtime override lives in the DB — that is what "set by the ADMIN" means
        effectivePolicyIs(rule, "DB");
    }

    @Then("the effective memes purge policy is {string}, set by the deployment")
    public void effectivePolicySetByDeployment(String rule) {
        // no override in the DB, so the env default of the deployment answers
        effectivePolicyIs(rule, "ENV");
    }

    private void effectivePolicyIs(String rule, String source) {
        Response current = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.ADMIN_TOKEN)
                .get("/admin/purge-policy");
        assertEquals(200, current.statusCode());
        assertEquals(rule, current.jsonPath().getString("effective"));
        assertEquals(source, current.jsonPath().getString("source"));
    }

    @Then("the policy change is refused as not-an-ADMIN")
    public void changeRefused() {
        assertEquals(403, lastChange.statusCode());
        assertEquals("NOT_AN_ADMIN", lastChange.jsonPath().getString("status"));
    }
}
