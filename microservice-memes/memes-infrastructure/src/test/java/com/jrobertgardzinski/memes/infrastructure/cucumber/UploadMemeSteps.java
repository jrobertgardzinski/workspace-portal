package com.jrobertgardzinski.memes.infrastructure.cucumber;

import com.jrobertgardzinski.memes.infrastructure.TestAuthConfig;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP glue for {@code upload-meme.feature}, black-box against the running Spring app: a signed-in
 * user uploads a BMP; fetching (meme, thumbnail, gallery) deliberately carries no token — reads
 * are public.
 */
public class UploadMemeSteps {

    @LocalServerPort
    int port;

    private Response uploadResponse;
    private String memeId;

    @When("the USER uploads a BMP image")
    public void theUserUploadsABmpImage() throws Exception {
        uploadResponse = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .multiPart("file", "meme.bmp", bmp(), "image/bmp")
                .post("/memes");
        memeId = uploadResponse.jsonPath().getString("id");
    }

    @When("a GUEST tries to upload a BMP image")
    public void anAnonymousUserTriesToUpload() throws Exception {
        AuthSteps.lastAnonymousAttempt = RestAssured.given().port(port)
                .multiPart("file", "meme.bmp", bmp(), "image/bmp")
                .post("/memes");
    }

    @Then("the MEME is stored")
    public void theMemeIsStored() {
        assertEquals(201, uploadResponse.statusCode());
        assertNotNull(memeId, "no meme id returned");
    }

    @Then("fetching it without signing in returns a PNG")
    public void fetchingItAnonymouslyReturnsAPng() {
        Response fetched = RestAssured.given().port(port).get("/memes/" + memeId);
        assertEquals(200, fetched.statusCode());
        assertEquals("image/png", fetched.contentType());
        assertEquals((byte) 0x89, fetched.getBody().asByteArray()[0]); // PNG magic
    }

    @Then("fetching its thumbnail returns a PNG")
    public void fetchingItsThumbnailReturnsAPng() {
        Response thumbnail = RestAssured.given().port(port).get("/memes/" + memeId + "/thumbnail");
        assertEquals(200, thumbnail.statusCode());
        assertEquals("image/png", thumbnail.contentType());
        assertEquals((byte) 0x89, thumbnail.getBody().asByteArray()[0]); // PNG magic
    }

    @Then("the gallery lists it without signing in")
    public void theGalleryListsIt() {
        Response gallery = RestAssured.given().port(port).get("/memes");
        assertEquals(200, gallery.statusCode());
        List<String> ids = gallery.jsonPath().getList("id");
        assertTrue(ids.contains(memeId), "expected " + memeId + " in the gallery, got " + ids);
    }

    @When("the USER uploads a text file pretending to be an image")
    public void theUserUploadsATextFilePretendingToBeAnImage() {
        uploadResponse = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .multiPart("file", "meme.png", "words, not pixels".getBytes(), "image/png")
                .post("/memes");
    }

    @When("the USER uploads a tiny file declaring absurd image dimensions")
    public void theUserUploadsATinyFileDeclaringAbsurdDimensions() {
        byte[] liar = pngHeaderDeclaring(10_000, 10_000);
        assertTrue(liar.length < 100, "the hostile upload really is tiny on the wire");
        uploadResponse = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .multiPart("file", "colossus.png", liar, "image/png")
                .post("/memes");
    }

    @Then("the upload is turned away with a polite explanation")
    public void theUploadIsTurnedAwayWithAPoliteExplanation() {
        assertEquals(400, uploadResponse.statusCode(),
                "a refusal at the door, not a server crash — got: " + uploadResponse.asString());
        assertEquals("INVALID_IMAGE", uploadResponse.jsonPath().getString("status"),
                "a coded refusal, the one shape this boundary answers in");
        String explanation = uploadResponse.jsonPath().getString("detail");
        assertNotNull(explanation, "the refusal says why");
        assertFalse(explanation.contains("Exception"),
                "the explanation speaks to a person, not a stack trace: " + explanation);
    }

    @Then("the gallery still welcomes the next honest upload")
    public void theGalleryStillWelcomesTheNextHonestUpload() throws Exception {
        theUserUploadsABmpImage();
        theMemeIsStored();
        fetchingItAnonymouslyReturnsAPng();
    }

    /**
     * A valid PNG signature + a well-formed IHDR chunk (correct CRC) declaring the given
     * dimensions and nothing more — the same hostile-upload shape {@code WebImageOptimizerTest}
     * uses to pin the guard at unit level; here it arrives over HTTP like any real upload.
     */
    private static byte[] pngHeaderDeclaring(int width, int height) {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();     // type + payload, CRC-covered
        chunk.writeBytes("IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        chunk.writeBytes(java.nio.ByteBuffer.allocate(13)
                .putInt(width).putInt(height)
                .put((byte) 8)     // bit depth
                .put((byte) 2)     // colour type: truecolour
                .put((byte) 0).put((byte) 0).put((byte) 0)   // compression, filter, interlace
                .array());
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(chunk.toByteArray());
        png.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(13).array());     // IHDR payload length
        png.writeBytes(chunk.toByteArray());
        png.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return png.toByteArray();
    }

    private static byte[] bmp() throws Exception {
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", out);
        return out.toByteArray();
    }
}
