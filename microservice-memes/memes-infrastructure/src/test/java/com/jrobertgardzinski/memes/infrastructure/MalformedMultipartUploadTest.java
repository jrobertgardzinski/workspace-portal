package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An upload whose {@code Content-Type} says {@code multipart/form-data} and then names no boundary
 * is unparseable — and it is the CALLER who wrote it. The advice sorts refusals from faults and
 * "refuses to conflate the two", so this has to be a 400.
 *
 * <p>It was a 500. The multipart is resolved by {@code DispatcherServlet.checkMultipart} before any
 * handler is chosen, and the failure only becomes the 413-carrying {@code MaxUploadSizeExceeded}
 * when the container's message talks about size; "no multipart boundary was found" stays a plain
 * {@code MultipartException}, which carries no status of its own and which the advice did not name
 * — so Boot's default error page answered 500 and the service logged a fault of its own for a
 * request it had simply been handed wrong.
 *
 * <p>Driven over a REAL socket, because that is the only place this exists: MockMvc hands the
 * dispatcher a request whose parts are set by the test, so the container never parses anything and
 * the bug cannot appear.
 */
@Epic("Infrastructure")
@Feature("Error responses")
@Story("Malformed multipart")
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "memes.upload.rate-limit-per-minute=0")
class MalformedMultipartUploadTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("a multipart upload without a boundary is the caller's mistake, not ours")
    void a_multipart_without_a_boundary_is_refused_not_crashed_on() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/memes"))
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                        .header("Content-Type", "multipart/form-data")   // and no boundary
                        .POST(HttpRequest.BodyPublishers.ofByteArray("not-a-multipart".getBytes(StandardCharsets.UTF_8)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(),
                "a 500 tells the caller the server broke, and tells us to go looking: " + response.body());
        assertTrue(response.body().contains("MALFORMED_MULTIPART"),
                "the refusal names itself, like every other refusal at this boundary: " + response.body());
    }

    @Test
    @DisplayName("an upload that is too big is still 413, not swept into the 400")
    void an_oversized_upload_keeps_its_own_refusal() throws Exception {
        // the same exception FAMILY (MaxUploadSizeExceededException extends MultipartException),
        // so a handler written for the family would quietly demote this one to "malformed"
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/memes"))
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(oversizedPart()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode(), response.body());
    }

    private static final String BOUNDARY = "----memesTestBoundary";

    /** A well-formed multipart whose single file part is past {@code max-file-size} (10MB). */
    private static byte[] oversizedPart() {
        byte[] head = ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[11 * 1024 * 1024];
        byte[] whole = new byte[head.length + body.length + tail.length];
        System.arraycopy(head, 0, whole, 0, head.length);
        System.arraycopy(body, 0, whole, head.length, body.length);
        System.arraycopy(tail, 0, whole, head.length + body.length, tail.length);
        return whole;
    }
}
