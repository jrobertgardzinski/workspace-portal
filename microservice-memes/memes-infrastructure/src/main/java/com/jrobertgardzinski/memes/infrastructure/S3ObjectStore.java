package com.jrobertgardzinski.memes.infrastructure;

import com.jrobertgardzinski.memes.application.ObjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.util.Optional;

/**
 * Image bytes in object storage — the third {@link ObjectStore} adapter, same shape as the
 * database and filesystem ones. Active when {@code memes.blob-store=s3}; speaks the S3 API, so
 * it serves MinIO in the compose stack ({@code memes.s3.path-style=true} — MinIO routes by path,
 * not virtual host) and real S3 alike. The bucket is created at startup when missing, so a fresh
 * stack needs no manual step.
 */
@Component
@ConditionalOnProperty(name = "memes.blob-store", havingValue = "s3")
class S3ObjectStore implements ObjectStore {

    private final S3Client s3;
    private final String bucket;
    private final PendingBlobDeletes pending;

    @Autowired   // two constructors: Spring must be told the Environment one is the container's
    S3ObjectStore(Environment env, PendingBlobDeletes pending) {
        this(S3Client.builder()
                        .endpointOverride(URI.create(required(env, "memes.s3.endpoint")))
                        .region(Region.of(env.getProperty("memes.s3.region", "us-east-1")))
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                                required(env, "memes.s3.access-key"), required(env, "memes.s3.secret-key"))))
                        .forcePathStyle(env.getProperty("memes.s3.path-style", Boolean.class, true))
                        .build(),
                env.getProperty("memes.s3.bucket", "memes"),
                pending);
    }

    S3ObjectStore(S3Client s3, String bucket, PendingBlobDeletes pending) {
        this.s3 = s3;
        this.bucket = bucket;
        this.pending = pending;
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (BucketAlreadyOwnedByYouException exists) {
            // idempotent startup — the bucket survives restarts, we just make sure it's there
        }
    }

    @Override
    public void put(String key, byte[] data) {
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(data));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray());
        } catch (NoSuchKeyException absent) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        // the bucket is outside any DB transaction: within the delete/purge seam the object goes
        // only after the DB part commits, so a rollback never resurrects a meme row whose bytes
        // are already gone (same reasoning as the filesystem adapter). And because "after the commit"
        // used to mean "in this JVM's memory, or never", the obligation itself is written into the
        // transaction — this is the store where losing it means a person's photographs stay in a
        // bucket after they were told the deletion is done (PendingBlobDeletes).
        pending.deleteDurably(key, () -> s3.deleteObject(b -> b.bucket(bucket).key(key)));
    }

    private static String required(Environment env, String property) {
        String value = env.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when memes.blob-store=s3");
        }
        return value;
    }
}
