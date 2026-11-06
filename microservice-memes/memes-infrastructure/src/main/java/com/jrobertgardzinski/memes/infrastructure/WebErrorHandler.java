package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.image.InvalidImageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Sorts unhandled exceptions into "the caller sent something we refuse" (400) and "we broke" (500)
 * — and refuses to conflate the two. The image pipeline signals bad input with the dedicated
 * {@link InvalidImageException} (unreadable bytes, oversized dimensions), whose messages are
 * written for the uploader and safe to echo. A bare {@link UncheckedIOException} is the opposite:
 * it escapes the blob adapters when the DISK or bucket fails mid-read on a GET, so it maps to a
 * 500 with a generic body (the detail — internal paths included — goes to the log, not the wire).
 * A bare {@link IllegalArgumentException} stays a 400, but likewise with a generic body: those
 * messages were written for developers and may leak internals.
 *
 * <p>ONE SHAPE for every refusal this service writes: {@code {"status": "<CODE>", "detail":
 * "<sentence>"}} — the code is what a client branches on, the detail is what a person reads. Half
 * of these used to answer {@code {"error": "<sentence>"}} instead, so the boundary spoke two
 * languages and the image pipeline's refusals — the only ones with something useful to say — were
 * the half with no code at all. A {@link MultipartException} is a
 * refusal too, and one no controller ever sees: the multipart is resolved before handler mapping,
 * so an upload the container cannot parse never gets that far. Controllers that want a MORE
 * PRECISE code (e.g. tagging's {@code INVALID_TAG}) keep their local catches — those run first,
 * this advice is the uniform floor under everything else.
 */
@RestControllerAdvice
class WebErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WebErrorHandler.class);

    @ExceptionHandler(InvalidImageException.class)
    ResponseEntity<Map<String, String>> refusedImage(InvalidImageException refused) {
        // the one exception whose message is meant for the caller — echo it as the explanation
        return ResponseEntity.badRequest().body(Map.of("status", "INVALID_IMAGE",
                "detail", String.valueOf(refused.getMessage())));
    }

    @ExceptionHandler(ImageDecodeOverloadedException.class)
    ResponseEntity<Map<String, String>> decodeSaturated(ImageDecodeOverloadedException busy) {
        // the global decode cap (ConcurrencyGuardedImageOptimizer) is full: neither the caller's
        // fault nor a fault of ours — "try again shortly". 429 + Retry-After, deliberately the
        // same refusal shape as the per-user upload rate limit in MemeController, so clients
        // handle one throttling contract, not two; the body's status distinguishes the axes.
        LOG.warn("refusing request: {}", busy.getMessage());
        return ResponseEntity.status(429).header("Retry-After", "5")
                .body(Map.of("status", "BUSY", "detail", "too many images are being processed right now"));
    }

    @ExceptionHandler(ImageDecodeInterruptedException.class)
    ResponseEntity<Map<String, String>> decodeInterrupted(ImageDecodeInterruptedException torn) {
        // the permit WAIT was interrupted — this worker is being torn down (shutdown,
        // cancellation), not throttling anyone. 503, not 429: "this instance is unavailable, go
        // elsewhere", where the 429 above means "I am healthy, just busy — retry me shortly".
        LOG.warn("refusing request: {}", torn.getMessage());
        return ResponseEntity.status(503)
                .body(Map.of("status", "UNAVAILABLE", "detail", "the server is shutting this worker down"));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Map<String, String>> malformedMultipart(MultipartException unparseable) {
        // The upload never reached a controller: the multipart is resolved before handler mapping,
        // and a Content-Type of "multipart/form-data" with no boundary makes the container refuse
        // to parse it. That refusal carries no status of its own, so it used to fall through to
        // Boot's 500 — the server confessing to a fault for a request the caller wrote wrong.
        LOG.warn("refusing request: {}", unparseable.getMessage());
        return ResponseEntity.badRequest().body(Map.of("status", "MALFORMED_MULTIPART",
                "detail", "the upload is not a readable multipart/form-data request"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException oversized) {
        // A subclass of the above, and a DIFFERENT refusal: the request was perfectly readable,
        // there was just too much of it. Named explicitly so the family handler cannot demote it —
        // the body matches RejectOversizedUploadFilter's, which refuses the same thing earlier
        // when the caller declares the length up front.
        LOG.warn("refusing request: {}", oversized.getMessage());
        return ResponseEntity.status(413).body(Map.of("status", "TOO_LARGE",
                "detail", "the upload is larger than this service accepts"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> refusedInput(IllegalArgumentException refused) {
        // still the caller's fault (an argument WE validated and rejected), but the raw message
        // was never vetted for the wire — keep the body generic, put the detail in the log
        LOG.warn("refusing request: {}", refused.getMessage());
        return ResponseEntity.badRequest().body(Map.of("status", "INVALID_REQUEST",
                "detail", "invalid request"));
    }

    @ExceptionHandler(UncheckedIOException.class)
    ResponseEntity<Map<String, String>> storageFailure(UncheckedIOException failure) {
        // NOT the caller's upload: input-decode failures surface as InvalidImageException above.
        // What reaches this handler is the blob store dying under a request (disk fault on a GET,
        // unreachable bucket) — a server error, answered without internal paths or messages.
        LOG.error("I/O failure while handling a request", failure);
        return ResponseEntity.internalServerError().body(Map.of("status", "STORAGE_ERROR",
                "detail", "internal storage error"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> serverFault(IllegalStateException fault) {
        // "we broke", by our own admission: use cases raise IllegalStateException for states the
        // server should never be in — e.g. MakeThumbnail finding STORED bytes that no longer
        // decode (data corruption, not a bad request). 500 with a generic body; the message may
        // name meme ids and internals, so it goes to the log, never the wire.
        LOG.error("server-side invariant broken while handling a request", fault);
        return ResponseEntity.internalServerError().body(Map.of("status", "INTERNAL_ERROR",
                "detail", "internal error"));
    }
}
