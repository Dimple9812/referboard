package com.referboard.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis for integration tests, following the same {@code it.db} switch as {@link TestPostgres}:
 * Testcontainers {@code redis:7-alpine} by default, or a local server ({@code IT_REDIS_HOST}/{@code IT_REDIS_PORT},
 * default localhost:6379) with {@code -Dit.db=local}. Local mode picks a random logical database (1–15) so
 * concurrent runs don't share keys.
 */
public final class TestRedis {

    private static String host;
    private static int port;
    private static int database;

    private TestRedis() {
    }

    public static synchronized void register(DynamicPropertyRegistry registry) {
        if (host == null) {
            String mode = System.getProperty("it.db", System.getenv().getOrDefault("IT_DB", "testcontainers"));
            if ("local".equalsIgnoreCase(mode)) {
                host = System.getenv().getOrDefault("IT_REDIS_HOST", "localhost");
                port = Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379"));
                database = ThreadLocalRandom.current().nextInt(1, 16);
            } else {
                @SuppressWarnings("resource")
                GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                        .withExposedPorts(6379);
                redis.start();
                host = redis.getHost();
                port = redis.getMappedPort(6379);
                database = 0;
            }
        }
        registry.add("spring.data.redis.host", () -> host);
        registry.add("spring.data.redis.port", () -> port);
        registry.add("spring.data.redis.database", () -> database);
    }
}
