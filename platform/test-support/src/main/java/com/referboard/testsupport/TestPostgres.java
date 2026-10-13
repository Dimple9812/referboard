package com.referboard.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * One PostgreSQL 16 + pgvector database per test JVM, wired into Spring via {@link #register}.
 *
 * <p>Mode is chosen by the {@code it.db} system property (Maven: {@code -Dit.db=...}) or {@code IT_DB} env var:
 * <ul>
 *   <li>{@code testcontainers} (default) — starts {@code pgvector/pgvector:pg16} with Testcontainers.</li>
 *   <li>{@code local} — uses an existing server at {@code IT_DB_URL} (default
 *       {@code jdbc:postgresql://localhost:5432/}) as {@code IT_DB_USER}/{@code IT_DB_PASSWORD}
 *       (default postgres/postgres). A throwaway database is created per run so runs never collide.</li>
 * </ul>
 */
public final class TestPostgres {

    private static final String IMAGE = "pgvector/pgvector:pg16";
    private static Database database;

    private TestPostgres() {
    }

    public record Database(String baseUrl, String username, String password) {
        public String urlForSchema(String schema) {
            return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
        }
    }

    public static synchronized Database get() {
        if (database == null) {
            database = "local".equalsIgnoreCase(mode()) ? startLocal() : startContainer();
        }
        return database;
    }

    /** Points datasource, Flyway and Hibernate at {@code schema} in the shared test database. */
    public static void register(DynamicPropertyRegistry registry, String schema) {
        Database db = get();
        registry.add("spring.datasource.url", () -> db.urlForSchema(schema));
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }

    private static String mode() {
        String fromProperty = System.getProperty("it.db");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv("IT_DB");
        return fromEnv == null ? "testcontainers" : fromEnv;
    }

    @SuppressWarnings("resource")
    private static Database startContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("referboard_test")
                .withUsername("test")
                .withPassword("test");
        container.start(); // stopped by Testcontainers' Ryuk when the JVM exits
        Database db = new Database(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        createVectorExtension(db.baseUrl(), db.username(), db.password());
        return db;
    }

    private static Database startLocal() {
        String root = env("IT_DB_URL", "jdbc:postgresql://localhost:5432/");
        String user = env("IT_DB_USER", "postgres");
        String password = env("IT_DB_PASSWORD", "postgres");
        String dbName = "rb_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(root + "postgres", user, password);
             Statement s = c.createStatement()) {
            s.execute("create database " + dbName);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test database on " + root, e);
        }
        String url = root + dbName;
        createVectorExtension(url, user, password);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> dropQuietly(root, user, password, dbName)));
        return new Database(url, user, password);
    }

    private static void createVectorExtension(String url, String user, String password) {
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("create extension if not exists vector schema public");
        } catch (SQLException e) {
            throw new IllegalStateException("pgvector extension is not available on the test database", e);
        }
    }

    private static void dropQuietly(String root, String user, String password, String dbName) {
        try (Connection c = DriverManager.getConnection(root + "postgres", user, password);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + dbName + " with (force)");
        } catch (SQLException ignored) {
            // best effort cleanup
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
