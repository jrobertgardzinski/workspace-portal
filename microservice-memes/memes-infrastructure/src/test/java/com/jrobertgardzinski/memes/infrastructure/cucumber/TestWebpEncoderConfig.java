package com.jrobertgardzinski.memes.infrastructure.cucumber;

import com.jrobertgardzinski.memes.application.ImageEncoder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Optional;

/**
 * Test double for the image-encoder microservice, the same pattern as {@link
 * com.jrobertgardzinski.memes.infrastructure.TestAuthConfig} for microservice-security: scenarios
 * that ask for WebP get WebP without a real encoder running. Without it the unconfigured {@code
 * HttpImageEncoder} answers {@code empty}, ServeMeme falls back to PNG, and no WebP variant would
 * ever be cached — so the "not a byte remains" scenario could not see a variant to sweep. Steps
 * that never send {@code Accept: image/webp} are untouched.
 */
@TestConfiguration
public class TestWebpEncoderConfig {

    /** What the stub serves as WebP — recognisable RIFF/WEBP magic, no real encoding. */
    public static final byte[] FAKE_WEBP = "RIFF....WEBPVP8 ".getBytes();

    @Bean
    @Primary
    ImageEncoder stubImageEncoder() {
        return png -> Optional.of(FAKE_WEBP);
    }
}
