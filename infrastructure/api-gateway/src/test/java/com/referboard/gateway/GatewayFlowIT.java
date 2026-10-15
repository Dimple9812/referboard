package com.referboard.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestRedis;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway in front of a MockWebServer that plays every service (echoing what it received) and serves the
 * JWKS, with real Redis for rate limiting. Proves routing, edge JWT validation, header handling, CORS, rate
 * limits and error shapes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.gateway.cors-allowed-origins=https://app.referboard.test",
        "referboard.gateway.rate-limits.default-per-minute=5",
        "referboard.gateway.rate-limits.auth-per-minute=3",
        "referboard.gateway.rate-limits.expensive-per-minute=2"
})
class GatewayFlowIT {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static MockWebServer backend;

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) throws IOException {
        TestRedis.register(registry);
        backend = new MockWebServer();
        backend.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                if (r.getPath().equals("/.well-known/jwks.json")) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(TestJwt.jwksJson());
                }
                if (r.getPath().equals("/v3/api-docs")) {
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"openapi\":\"3.1.0\",\"servers\":[{\"url\":\"http://10.1.2.3:8083\"}]}");
                }
                if (r.getPath().equals("/api/jobs/boom")) {
                    return new MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
                            .setBody("{\"code\":\"CONFLICT_FROM_SERVICE\"}");
                }
                Map<String, Object> echo = new LinkedHashMap<>();
                echo.put("method", r.getMethod());
                echo.put("path", r.getPath());
                echo.put("authorization", r.getHeader("Authorization"));
                echo.put("correlationId", r.getHeader("X-Correlation-Id"));
                echo.put("forwardedHost", r.getHeader("X-Forwarded-Host"));
                echo.put("body", r.getBody().readUtf8());
                try {
                    // Services echo the correlation id too (common-lib); the client must still see it once.
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setHeader("X-Correlation-Id", String.valueOf(r.getHeader("X-Correlation-Id")))
                            .setBody(MAPPER.writeValueAsString(echo));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        backend.start();
        String url = "http://localhost:" + backend.getPort();
        for (String s : new String[] {"auth", "profile", "jobs", "ingestion", "referral", "analyzer", "tracker"}) {
            registry.add("referboard.gateway.services." + s, () -> url);
        }
        registry.add("referboard.gateway.services.notification", () -> "http://localhost:1");   // nothing listens
        registry.add("referboard.security.jwt.jwk-set-uri", () -> url + "/.well-known/jwks.json");
    }

    @LocalServerPort
    int port;
    @Autowired
    ReactiveStringRedisTemplate redis;

    WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(20))
                .build();
    }

    @BeforeAll
    static void clean(@Autowired ReactiveStringRedisTemplate redis) {
        redis.keys("rl:*").flatMap(redis::delete).blockLast(Duration.ofSeconds(5));
    }

    @AfterAll
    static void stop() throws IOException {
        backend.shutdown();
    }

    @Test
    void routesAuthenticatedCallsAndPassesTheTokenThrough() throws Exception {
        String token = TestJwt.tokenFor(UUID.randomUUID());
        JsonNode echo = json(client().get().uri("/api/jobs?q=java").header("Authorization", "Bearer " + token)
                .header("X-Correlation-Id", "web-12345678").exchange().expectStatus().isOk()
                .expectHeader().values("X-Correlation-Id", v -> assertThat(v).containsExactly("web-12345678"))
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectBody().returnResult());
        assertThat(echo.get("path").asText()).isEqualTo("/api/jobs?q=java");
        assertThat(echo.get("authorization").asText()).isEqualTo("Bearer " + token);
        assertThat(echo.get("correlationId").asText()).isEqualTo("web-12345678");
        assertThat(echo.get("forwardedHost").asText()).startsWith("localhost");

        for (String path : new String[] {"/api/profile", "/api/referrals/contacts", "/api/analyses",
                "/api/resume-versions", "/api/applications/board"}) {
            assertThat(json(client().get().uri(path).header("Authorization", "Bearer " + TestJwt.tokenFor(UUID.randomUUID()))
                    .exchange().expectStatus().isOk().expectBody().returnResult()).get("path").asText()).isEqualTo(path);
        }
    }

    @Test
    void downstreamErrorsPassThroughUntouched() {
        client().get().uri("/api/jobs/boom").header("Authorization", "Bearer " + TestJwt.tokenFor(UUID.randomUUID()))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("CONFLICT_FROM_SERVICE");
    }

    @Test
    void rejectsMissingOrBadTokensAtTheEdge() throws Exception {
        int before = backend.getRequestCount();
        JsonNode missing = json(client().get().uri("/api/profile").exchange().expectStatus().isUnauthorized()
                .expectHeader().exists("X-Correlation-Id").expectBody().returnResult());
        assertThat(missing.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(missing.get("correlationId").asText()).hasSize(36);

        UUID u = UUID.randomUUID();
        client().get().uri("/api/profile").header("Authorization", "Bearer "
                + TestJwt.token(u, TestJwt.ISSUER, "someone-else", Instant.now().plusSeconds(600)))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/profile").header("Authorization", "Bearer "
                + TestJwt.token(u, "evil-issuer", TestJwt.AUDIENCE, Instant.now().plusSeconds(600)))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/profile").header("Authorization", "Bearer "
                + TestJwt.token(u, TestJwt.ISSUER, TestJwt.AUDIENCE, Instant.now().minusSeconds(600)))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/profile").header("Authorization", "Bearer not.a.jwt")
                .exchange().expectStatus().isUnauthorized();
        assertThat(backend.getRequestCount() - before).as("only the JWKS fetch reached the backend").isLessThanOrEqualTo(1);
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        assertThat(json(client().post().uri("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.c\"}").exchange().expectStatus().isOk().expectBody().returnResult())
                .get("body").asText()).isEqualTo("{\"email\":\"a@b.c\"}");
        client().get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.keys[0].kid").isEqualTo("test-key");
        client().get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void signInIsRateLimitedPerIp() throws Exception {
        redis.keys("rl:auth:*").flatMap(redis::delete).blockLast(Duration.ofSeconds(5));   // other tests sign up
        for (int i = 0; i < 3; i++) {
            client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                    .exchange().expectStatus().isOk();
        }
        EntityExchangeResult<byte[]> limited = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS).expectBody().returnResult();
        assertThat(limited.getResponseHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(Integer.parseInt(limited.getResponseHeaders().getFirst("Retry-After"))).isBetween(1, 60);
        assertThat(json(limited).get("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void usersHaveSeparateBucketsAndLlmCallsAStricterOne() {
        String alice = "Bearer " + TestJwt.tokenFor(UUID.randomUUID());
        String bob = "Bearer " + TestJwt.tokenFor(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            client().get().uri("/api/applications/board").header("Authorization", alice).exchange().expectStatus().isOk();
        }
        client().get().uri("/api/applications/board").header("Authorization", alice).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        client().get().uri("/api/applications/board").header("Authorization", bob).exchange().expectStatus().isOk();

        String carol = "Bearer " + TestJwt.tokenFor(UUID.randomUUID());
        for (int i = 0; i < 2; i++) {
            client().post().uri("/api/analyses").header("Authorization", carol).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}").exchange().expectStatus().isOk();
        }
        client().post().uri("/api/analyses").header("Authorization", carol).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        client().get().uri("/api/analyses").header("Authorization", carol).exchange()
                .expectStatus().isOk();   // reads use the default bucket
    }

    @Test
    void corsAllowsOnlyTheFrontend() {
        client().options().uri("/api/jobs").header(HttpHeaders.ORIGIN, "https://app.referboard.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.referboard.test")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        client().options().uri("/api/jobs").header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/jobs").header(HttpHeaders.ORIGIN, "https://app.referboard.test")
                .header("Authorization", "Bearer " + TestJwt.tokenFor(UUID.randomUUID()))
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.referboard.test")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, v -> assertThat(v).contains("X-Correlation-Id"));
    }

    @Test
    void gatewayFailuresAreJson() throws Exception {
        JsonNode down = json(client().get().uri("/api/notifications").header("Authorization", "Bearer "
                + TestJwt.tokenFor(UUID.randomUUID())).exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().returnResult());
        assertThat(down.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        JsonNode missing = json(client().get().uri("/nope").exchange().expectStatus().isNotFound().expectBody()
                .returnResult());
        assertThat(missing.get("code").asText()).isEqualTo("NOT_FOUND");
        client().get().uri("/actuator/env").exchange().expectStatus().isNotFound();
    }

    @Test
    void aggregatesApiDocs() {
        client().get().uri("/v3/api-docs/jobs").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.openapi").isEqualTo("3.1.0")
                .jsonPath("$.servers[0].url").isEqualTo("http://localhost:" + port);
        client().get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection();
    }

    private static JsonNode json(EntityExchangeResult<byte[]> r) throws IOException {
        return MAPPER.readTree(r.getResponseBody());
    }
}
