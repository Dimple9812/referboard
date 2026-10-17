package com.referboard.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestPostgres;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Interview prep over HTTP against real Postgres, embedded Kafka, a mock analyzer and a mock Claude API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.ai.llm.provider=anthropic",
        "referboard.ai.llm.anthropic.api-key=test-key",
        "referboard.ai.llm.anthropic.model=claude-test",
        "referboard.ai.llm.max-attempts=1",
})
@EmbeddedKafka(partitions = 1, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InterviewFlowIT {

    static final UUID USER = UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final UUID ANALYSIS = UUID.randomUUID();
    static final UUID GONE = UUID.randomUUID();
    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    static final AtomicBoolean llmDown = new AtomicBoolean(false);
    static final AtomicBoolean analyzerDown = new AtomicBoolean(false);
    static final List<String> authHeaders = new CopyOnWriteArrayList<>();
    static final Map<String, String> ids = new ConcurrentHashMap<>();
    static MockWebServer upstream;

    @TestConfiguration
    static class Jwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) throws IOException {
        TestPostgres.register(registry, "interview");
        upstream = new MockWebServer();
        upstream.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                try {
                    if (r.getPath().startsWith("/api/analyses/")) {
                        authHeaders.add(r.getHeader("Authorization"));
                        if (analyzerDown.get()) {
                            return new MockResponse().setResponseCode(503);
                        }
                        if (r.getPath().contains(GONE.toString())) {
                            return new MockResponse().setResponseCode(404).setBody("{\"code\":\"NOT_FOUND\"}");
                        }
                        return json(MAPPER.writeValueAsString(Fixtures.context()));
                    }
                    if (r.getPath().equals("/v1/messages")) {
                        if (llmDown.get()) {
                            return new MockResponse().setResponseCode(500);
                        }
                        return json(MAPPER.writeValueAsString(Map.of("id", "msg", "type", "message", "role", "assistant",
                                "model", "claude-test", "stop_reason", "tool_use", "usage", Map.of("input_tokens", 1, "output_tokens", 1),
                                "content", List.of(Map.of("type", "tool_use", "id", "t1", "name", "record_interview_questions",
                                        "input", Map.of("questions", List.of(
                                                Map.of("section", "ROLE", "question", "How would you make settlement reconciliation safe to re-run?",
                                                        "checks", "Idempotency in batch jobs.", "experienceIndex", 0, "bulletIndex", 2),
                                                Map.of("section", "TECHNICAL", "question", "Tell me about yourself.", "checks", "dup"))))))));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        upstream.start();
        String base = "http://localhost:" + upstream.getPort();
        registry.add("referboard.clients.analyzer-service-url", () -> base);
        registry.add("referboard.ai.llm.anthropic.base-url", () -> base);
    }

    @AfterAll
    static void stop() throws IOException {
        upstream.shutdown();
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Order(1)
    void buildsAPackFromTheAnalysisWithTheUsersOwnToken() throws Exception {
        JsonNode pack = json(call(HttpMethod.POST, "/api/interview-preps", Map.of("analysisId", ANALYSIS), TOKEN), 201);
        ids.put("pack", pack.get("id").asText());
        assertThat(authHeaders).last().isEqualTo("Bearer " + TOKEN);
        assertThat(pack.get("origin").asText()).isEqualTo("AI");
        assertThat(pack.get("jobTitle").asText()).isEqualTo("Senior Backend Engineer");
        List<JsonNode> items = list(pack.get("items"));
        assertThat(items.getFirst().get("section").asText()).isEqualTo("INTRO");
        assertThat(items).anySatisfy(i -> {
            assertThat(i.get("origin").asText()).isEqualTo("AI");
            assertThat(i.get("question").asText()).contains("settlement reconciliation");
            assertThat(i.get("evidence").asText()).isEqualTo("Moved reconciliation jobs to PostgreSQL partitioned tables");
        });
        assertThat(items).filteredOn(i -> i.get("question").asText().equals("Tell me about yourself.")).hasSize(1);
        assertThat(items).anySatisfy(i -> assertThat(i.get("question").asText()).isEqualTo("Have you worked with gRPC?"));
        // Sections stay in order: intro, technical, role, behavioural, gaps, questions to ask.
        List<String> sections = items.stream().map(i -> i.get("section").asText()).distinct().toList();
        assertThat(sections).containsExactly("INTRO", "TECHNICAL", "ROLE", "BEHAVIOURAL", "GAPS", "ASK");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'InterviewPrepCreated'",
                Integer.class)).isEqualTo(1);
        ids.put("item", items.get(1).get("id").asText());
        ids.put("itemVersion", items.get(1).get("version").asText());
    }

    @Test
    @Order(2)
    void notesAndConfidenceAreSavedWithVersions() throws Exception {
        String path = "/api/interview-preps/" + ids.get("pack") + "/items/" + ids.get("item");
        JsonNode item = json(call(HttpMethod.PATCH, path, Map.of("notes", "Talk about the settlement service.",
                "confidence", "PRACTISING", "version", Long.parseLong(ids.get("itemVersion"))), TOKEN), 200);
        assertThat(item.get("notes").asText()).isEqualTo("Talk about the settlement service.");
        assertThat(item.get("confidence").asText()).isEqualTo("PRACTISING");
        ResponseEntity<String> stale = call(HttpMethod.PATCH, path, Map.of("confidence", "CONFIDENT",
                "version", Long.parseLong(ids.get("itemVersion"))), TOKEN);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode summary = json(call(HttpMethod.GET, "/api/interview-preps", null, TOKEN), 200).get(0);
        assertThat(summary.get("practising").asInt()).isEqualTo(1);
        assertThat(summary.get("total").asInt()).isGreaterThan(20);
    }

    @Test
    @Order(3)
    void freeUsersGetThreePacksAMonthProIsUnlimited() throws Exception {
        llmDown.set(true);
        JsonNode rules = json(call(HttpMethod.POST, "/api/interview-preps", Map.of("analysisId", ANALYSIS), TOKEN), 201);
        llmDown.set(false);
        assertThat(rules.get("origin").asText()).isEqualTo("RULES");
        assertThat(rules.get("notices").toString()).contains("aren't available right now");
        json(call(HttpMethod.POST, "/api/interview-preps", Map.of("analysisId", ANALYSIS), TOKEN), 201);
        ResponseEntity<String> limited = call(HttpMethod.POST, "/api/interview-preps", Map.of("analysisId", ANALYSIS), TOKEN);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).contains("PREP_LIMIT").contains("go Pro");
        assertThat(json(call(HttpMethod.GET, "/api/interview-preps/usage", null, TOKEN), 200).get("remaining").asInt()).isZero();

        String pro = TestJwt.tokenWithRoles(USER, "USER", "PRO");
        assertThat(json(call(HttpMethod.GET, "/api/interview-preps/usage", null, pro), 200).get("unlimited").asBoolean()).isTrue();
        json(call(HttpMethod.POST, "/api/interview-preps", Map.of("analysisId", ANALYSIS), pro), 201);
    }

    @Test
    @Order(4)
    void upstreamProblemsAndIsolation() {
        String other = TestJwt.tokenFor(UUID.randomUUID());
        assertThat(call(HttpMethod.POST, "/api/interview-preps", Map.of("analysisId", GONE), other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        analyzerDown.set(true);
        ResponseEntity<String> down = call(HttpMethod.POST, "/api/interview-preps", Map.of("analysisId", ANALYSIS), other);
        analyzerDown.set(false);
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(call(HttpMethod.GET, "/api/interview-preps/" + ids.get("pack"), null, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/interview-preps", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.DELETE, "/api/interview-preps/" + ids.get("pack"), null, TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, h), String.class);
    }

    private static JsonNode json(ResponseEntity<String> r, int expected) throws IOException {
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(expected);
        return MAPPER.readTree(r.getBody());
    }

    private static List<JsonNode> list(JsonNode a) {
        List<JsonNode> out = new java.util.ArrayList<>();
        a.forEach(out::add);
        return out;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
