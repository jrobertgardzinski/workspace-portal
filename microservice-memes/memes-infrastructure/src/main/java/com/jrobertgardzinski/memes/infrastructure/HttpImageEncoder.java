package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ImageEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Calls the image-encoder microservice (POST /encode?format=webp). Unconfigured, unreachable, or a
 * non-200 all collapse to {@code empty} — {@link com.jrobertgardzinski.memes.application.ServeMeme}
 * then serves the PNG, so the gallery never depends on the encoder being up.
 */
@Component
class HttpImageEncoder implements ImageEncoder {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800)).build();
    private final String baseUrl;
    private final int quality;

    HttpImageEncoder(@Value("${memes.encoder.url:}") String baseUrl,
                     @Value("${memes.encoder.webp-quality:82}") int quality) {
        this.baseUrl = baseUrl;
        this.quality = quality;
    }

    @Override
    public Optional<byte[]> toWebp(byte[] png) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            String cid = org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY);
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create(baseUrl + "/encode?format=webp&quality=" + quality))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/octet-stream");
            if (cid != null) {
                request.header(CorrelationIdFilter.HEADER, cid);   // trace across services
            }
            HttpResponse<byte[]> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofByteArray(png)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (InterruptedException interrupted) {
            // not "the encoder is down": the CALLER's thread was told to stop. Restore the flag
            // so the shutdown keeps propagating, and fall back to PNG like any other miss
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception down) {
            return Optional.empty();
        }
    }
}
