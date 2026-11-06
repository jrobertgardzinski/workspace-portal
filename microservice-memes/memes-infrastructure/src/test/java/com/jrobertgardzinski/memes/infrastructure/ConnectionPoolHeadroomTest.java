package com.jrobertgardzinski.memes.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection pool, against the number of uploads this service admits at once.
 *
 * <p>{@code JdbcMemeRepository.save} is transactional and puts the image bytes inside that
 * transaction, so with {@code MEMES_BLOB_STORE=s3} an admitted upload holds its pooled connection
 * for the whole round trip to the bucket. The upload ceiling (8) and the request-thread ceiling
 * (32) were both chosen so the arithmetic could be checked; the pool was left at Hikari's default
 * of 10, which is the one number in that sum nobody picked — and it left two connections for the
 * whole gallery whenever MinIO was slow.
 */
@Epic("Infrastructure")
@Feature("Connection pool")
@SpringBootTest(classes = MemesApplication.class)
class ConnectionPoolHeadroomTest {

    @Autowired
    DataSource dataSource;

    @Value("${memes.upload.concurrency}")
    int admittedUploads;

    @Test
    @DisplayName("uploads holding a connection across the bucket cannot take more than half the pool")
    void the_pool_leaves_room_for_the_gallery() {
        int pool = ((HikariDataSource) dataSource).getMaximumPoolSize();

        assertTrue(pool >= 2 * admittedUploads,
                "a pool of " + pool + " against " + admittedUploads + " uploads that each hold a"
                        + " connection for as long as the bucket takes: the gallery is left with "
                        + (pool - admittedUploads) + " for its 32 request threads");
    }
}
