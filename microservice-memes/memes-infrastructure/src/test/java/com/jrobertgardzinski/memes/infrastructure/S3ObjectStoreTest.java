package com.jrobertgardzinski.memes.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S3 adapter against a real MinIO (Testcontainers) — the port's contract is a narrow
 * round-trip: put/get/delete by key, absent key = empty. Constructing the store twice proves the
 * create-bucket-at-startup step is idempotent. Skipped where docker is absent; the compose stack
 * exercises the same adapter live.
 */
@Epic("Infrastructure")
@Feature("Object store")
@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStoreTest {

    private static final String USER = "memes";
    private static final String PASSWORD = "supersecret";

    /**
     * MinIO from bitnamilegacy/, and NOT through Testcontainers' {@code MinIOContainer}.
     *
     * <p>The registry first: MinIO has put its own images behind a login. Docker Hub stopped
     * answering anonymous pulls in September 2026, quay.io followed on 2026-09-24 with a 401 for
     * every tag, and ghcr.io and public.ecr.aws never carried it — so every CI run failed on
     * FETCHING the image rather than on anything this test is about. Bitnami's build from source
     * is the only public copy left; read the namespace, it is frozen and will never get another
     * update. The tag stays pinned because a floating one would make this suite's result depend
     * on the day. The same image and the same three differences are in the compose stack.
     *
     * <p>And that is why the container is a plain {@link GenericContainer}:
     * {@code MinIOContainer} hands the image {@code server --console-address :9001 /data}, which
     * upstream's entrypoint understands and Bitnami's does not — it runs the server itself and
     * would try to exec that as a program. Three lines of configuration are cheaper than a
     * subclass that fights its own helper.
     */
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("bitnamilegacy/minio:2025.7.23-debian-12-r5"))
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200));

    static S3ObjectStore store;

    @BeforeAll
    static void connect() {
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(USER, PASSWORD)))
                .forcePathStyle(true)
                .build();
        store = new S3ObjectStore(s3, "memes", PendingBlobDeletes.none());
        new S3ObjectStore(s3, "memes", PendingBlobDeletes.none());   // second startup against an existing bucket must not throw
    }

    @Test
    @DisplayName("bytes round-trip by key; deletion leaves nothing behind")
    void round_trips() {
        byte[] bytes = "a very good meme".getBytes(StandardCharsets.UTF_8);

        store.put("meme-1", bytes);
        assertArrayEquals(bytes, store.get("meme-1").orElseThrow());

        store.delete("meme-1");
        assertTrue(store.get("meme-1").isEmpty());
    }

    @Test
    @DisplayName("an absent key is empty, not an error")
    void absent_is_empty() {
        assertTrue(store.get("never-uploaded").isEmpty());
    }
}
