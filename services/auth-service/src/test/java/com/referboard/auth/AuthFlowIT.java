package com.referboard.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.events.Topics;
import com.referboard.events.UserRegisteredEvent;
import com.referboard.testsupport.TestPostgres;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over HTTP against real PostgreSQL (Flyway-migrated) and an embedded Kafka broker:
 * signup → /me → refresh rotation → reuse detection → logout, lockout, validation errors, JWKS,
 * and the UserRegistered event travelling outbox → Kafka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.auth.refresh-cookie.secure=false",
        "referboard.auth.jwt.key-dir=target/it-keys"
})
@EmbeddedKafka(partitions = 1, topics = Topics.USER_REGISTERED, kraft = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class AuthFlowIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, "auth");
    }

    private static Consumer<String, String> consumer;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void subscribe(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("auth-it-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, Topics.USER_REGISTERED);
    }

    @AfterAll
    static void close() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void fullSessionLifecycle() throws Exception {
        String email = "it-" + UUID.randomUUID() + "@example.com";

        // signup
        ResponseEntity<String> signup = post("/api/auth/signup",
                Map.of("email", email, "password", "Passw0rdOK", "fullName", "Asha Rao"), null);
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = mapper.readTree(signup.getBody());
        String access = body.get("accessToken").asText();
        assertThat(body.has("refreshToken")).as("refresh token must not be in the body").isFalse();
        String cookie1 = refreshCookie(signup);
        assertThat(signup.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");

        // /me with the access token
        ResponseEntity<String> me = get("/api/auth/me", access);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(me.getBody()).get("email").asText()).isEqualTo(email);

        // refresh rotates the cookie
        ResponseEntity<String> refreshed = post("/api/auth/refresh", null, cookie1);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie2 = refreshCookie(refreshed);
        assertThat(cookie2).isNotEqualTo(cookie1);

        // replaying the old token is treated as theft: rejected, and the whole family is revoked
        assertThat(post("/api/auth/refresh", null, cookie1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/auth/refresh", null, cookie2).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // a fresh login works and logout revokes it
        ResponseEntity<String> login = post("/api/auth/login", Map.of("email", email.toUpperCase(),
                "password", "Passw0rdOK"), null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie3 = refreshCookie(login);
        assertThat(post("/api/auth/logout", null, cookie3).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/api/auth/refresh", null, cookie3).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // the raw refresh token is never stored
        String raw = cookie3.substring(cookie3.indexOf('=') + 1);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, raw))
                .isZero();

        // UserRegistered reached Kafka through the outbox
        UserRegisteredEvent event = awaitRegisteredEvent(email);
        assertThat(event.fullName()).isEqualTo("Asha Rao");
        assertThat(event.authProvider()).isEqualTo("LOCAL");
        assertThat(event.metadata().producer()).isEqualTo("auth-service");
    }

    @Test
    void duplicateSignupIsConflict() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        Map<String, String> req = Map.of("email", email, "password", "Passw0rdOK", "fullName", "Dup");
        assertThat(post("/api/auth/signup", req, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("/api/auth/signup",
                Map.of("email", email.toUpperCase(), "password", "Passw0rdOK", "fullName", "Dup"), null);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("EMAIL_TAKEN");
    }

    @Test
    void proUsersGetTheProRoleInTheirToken() throws Exception {
        String email = "pro-" + UUID.randomUUID() + "@example.com";
        post("/api/auth/signup", Map.of("email", email, "password", "Passw0rdOK", "fullName", "Pro"), null);
        jdbc.update("update users set role = 'PRO' where email = ?", email);
        ResponseEntity<String> login = post("/api/auth/login", Map.of("email", email, "password", "Passw0rdOK"), null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login.getBody())
                .get("accessToken").asText();
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).contains("\"roles\":[\"PRO\"]");
    }

    @Test
    void accountLocksAfterRepeatedFailures() {
        String email = "lock-" + UUID.randomUUID() + "@example.com";
        post("/api/auth/signup", Map.of("email", email, "password", "Passw0rdOK", "fullName", "Lock"), null);

        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/auth/login", Map.of("email", email, "password", "wrongpass1"), null)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<String> locked = post("/api/auth/login",
                Map.of("email", email, "password", "Passw0rdOK"), null);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getBody()).contains("ACCOUNT_LOCKED");
    }

    @Test
    void validationErrorsUseTheStandardErrorShape() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", "it-correlation-123");
        ResponseEntity<String> response = rest.exchange("/api/auth/signup", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "not-an-email", "password", "short", "fullName", ""), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = mapper.readTree(response.getBody());
        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("correlationId").asText()).isEqualTo("it-correlation-123");
        assertThat(error.get("fieldErrors").size()).isEqualTo(4);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("it-correlation-123");
    }

    @Test
    void protectedEndpointsRequireAValidToken() {
        assertThat(get("/api/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> bad = get("/api/auth/me", "eyJhbGciOiJSUzI1NiJ9.e30.invalid");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody()).contains("UNAUTHENTICATED");
    }

    @Test
    void publishesJwks() throws Exception {
        ResponseEntity<String> jwks = rest.getForEntity("/.well-known/jwks.json", String.class);
        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode key = mapper.readTree(jwks.getBody()).get("keys").get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.get("alg").asText()).isEqualTo("RS256");
        assertThat(key.has("d")).as("private exponent must never be published").isFalse();
    }

    @Test
    void googleSignInIsDisabledWithoutClientId() {
        ResponseEntity<String> response = post("/api/auth/google", Map.of("idToken", "x"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("GOOGLE_LOGIN_DISABLED");
    }

    private UserRegisteredEvent awaitRegisteredEvent(String email) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                UserRegisteredEvent event = mapper.readValue(record.value(), UserRegisteredEvent.class);
                if (email.equals(event.email())) {
                    assertThat(record.key()).isEqualTo(event.userId().toString());
                    return event;
                }
            }
        }
        throw new AssertionError("UserRegistered event for " + email + " not published");
    }

    private ResponseEntity<String> post(String path, Object body, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String refreshCookie(ResponseEntity<String> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull().startsWith("rb_refresh=");
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
