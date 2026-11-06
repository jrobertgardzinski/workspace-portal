package com.jrobertgardzinski.memes.infrastructure.cucumber;

import com.jrobertgardzinski.memes.infrastructure.MemesApplication;
import com.jrobertgardzinski.memes.infrastructure.TestAuthConfig;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the Spring application on a random port for Cucumber scenarios; step-defs share this
 * context. {@link TestAuthConfig} stubs the security gate, so "signed in" means presenting its
 * well-known test token. The upload ceiling is disabled here — all scenarios share one context and
 * one author uploads across many of them, so the per-minute limit would spuriously throttle; it is
 * exercised in isolation by {@code UploadRateLimitTest}. {@link TestWebpEncoderConfig} stubs the
 * image-encoder microservice the same way, so WebP-hungry scenarios need no encoder running.
 */
@CucumberContextConfiguration
@SpringBootTest(classes = {MemesApplication.class, TestAuthConfig.class, TestWebpEncoderConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "memes.upload.rate-limit-per-minute=0")
public class CucumberSpringConfiguration {
}
