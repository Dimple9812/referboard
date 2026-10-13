package com.referboard.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.render.PdfRenderer;
import com.referboard.events.Topics;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestPostgres;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ByteArrayResource;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JD Match Analyzer end to end against real PostgreSQL and embedded Kafka. profile-service, job-service and
 * the Anthropic API are a MockWebServer (switchable to "LLM down" to exercise the rule-based path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.analyzer.free-analyses-per-month=4",
        "referboard.ai.llm.provider=anthropic",
        "referboard.ai.llm.anthropic.api-key=test-key",
        "referboard.ai.llm.anthropic.model=claude-test",
        "referboard.ai.llm.max-attempts=1",
        "referboard.ai.embedding.provider=hashing"
})
@EmbeddedKafka(partitions = 1, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.RESUME_VERSION_CREATED})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnalyzerFlowIT {

    static final UUID USER = UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final String ADMIN = TestJwt.tokenWithRoles(UUID.randomUUID(), "USER", "ADMIN");
    static final UUID RESUME = UUID.randomUUID();
    static final UUID JOB = UUID.randomUUID();
    static final AtomicBoolean llmDown = new AtomicBoolean(false);
    static final AtomicInteger jdParseCalls = new AtomicInteger();
    static final ObjectMapper MAPPER = new ObjectMapper();

    static MockWebServer upstream;
    static Consumer<String, String> consumer;
    static final Map<String, String> ids = new HashMap<>();

    @TestConfiguration
    static class Jwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) throws IOException {
        TestPostgres.register(registry, "analyzer");
        byte[] resumePdf = PdfRenderer.render(Fixtures.resume());
        upstream = new MockWebServer();
        upstream.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = r.getPath();
                try {
                    if (path.equals("/api/profile/resumes")) {
                        return json(MAPPER.writeValueAsString(List.of(Map.of("id", RESUME.toString(),
                                "fileName", "Asha_Rao.pdf", "contentType", "application/pdf", "sizeBytes", resumePdf.length,
                                "parseStatus", "PARSED", "primary", true, "createdAt", "2026-09-01T00:00:00Z"))));
                    }
                    if (path.equals("/api/profile/resumes/" + RESUME + "/parsed")) {
                        return json(parsedResume());
                    }
                    if (path.equals("/api/profile/resumes/" + RESUME + "/file")) {
                        return new MockResponse().setHeader("Content-Type", "application/pdf")
                                .setBody(new Buffer().write(resumePdf));
                    }
                    if (path.equals("/api/jobs/" + JOB)) {
                        return json(MAPPER.writeValueAsString(Map.of("job", Map.of("id", JOB.toString(),
                                        "title", "Senior Backend Engineer", "companyName", "Northwind Payments",
                                        "skills", List.of()),
                                "descriptionHtml", "<h2>What you'll do</h2><ul><li>Build payment services in Java</li>"
                                        + "<li>Own Kafka consumers</li></ul><h2>Requirements</h2><ul>"
                                        + "<li>4+ years with Java and Spring Boot</li><li>PostgreSQL</li></ul>"
                                        + "<p>We are an equal opportunity employer and welcome everyone to apply here.</p>",
                                "status", "OPEN")));
                    }
                    if (path.equals("/v1/messages")) {
                        if (llmDown.get()) {
                            return new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}");
                        }
                        String body = r.getBody().readUtf8();
                        if (body.contains("record_job_requirements")) {
                            jdParseCalls.incrementAndGet();
                            return json(toolUse("record_job_requirements", jdParse()));
                        }
                        return json(toolUse("record_resume_suggestions", suggestions()));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        upstream.start();
        String base = "http://localhost:" + upstream.getPort();
        registry.add("referboard.clients.profile-service-url", () -> base);
        registry.add("referboard.clients.job-service-url", () -> base);
        registry.add("referboard.ai.llm.anthropic.base-url", () -> base);
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void subscribe(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("analyzer-it-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromEmbeddedTopics(consumer, Topics.RESUME_VERSION_CREATED);
    }

    @AfterAll
    static void close() throws IOException {
        consumer.close();
        upstream.shutdown();
    }

    @Test
    @Order(1)
    void analysesAPastedJdWithAGroundedReport() throws Exception {
        JsonNode r = json(post("/api/analyses", Map.of("jdText", Fixtures.backendJd(), "companyName", "Northwind Payments"),
                TOKEN), 201);
        ids.put("analysis", r.get("id").asText());

        assertThat(r.get("jdParser").asText()).isEqualTo("LLM");
        assertThat(r.at("/parsedJd/requiredSkills").toString()).as("'Rust' isn't in the JD: dropped")
                .doesNotContain("rust").contains("grpc");
        assertThat(r.at("/parsedJd/keywords").toString()).doesNotContain("blockchain").contains("payments");
        assertThat(r.at("/resume/name").asText()).isEqualTo("Asha_Rao.pdf");
        assertThat(r.get("score").asInt()).isBetween(50, 90);
        assertThat(r.get("verdict").asText()).isIn("GOOD", "PARTIAL", "STRONG");
        assertThat(r.get("breakdown")).hasSize(6);
        assertThat(r.at("/breakdown/0/weight").asInt()).isEqualTo(40);
        assertThat(r.at("/missingSkills/required").toString()).contains("grpc");
        assertThat(r.at("/missingSkills/weaklyEvidenced").toString()).contains("kafka");

        Map<String, JsonNode> checks = byKey(r.get("atsChecks"), "id");
        assertThat(checks.get("LENGTH").get("detail").asText()).as("page count read from the PDF").isEqualTo("1 page.");
        assertThat(checks.get("SKILL_WORDING").get("status").asText()).isEqualTo("WARN");

        List<JsonNode> suggestions = list(r.get("suggestions"));
        assertThat(suggestions).anySatisfy(s -> {
            assertThat(s.get("type").asText()).isEqualTo("ADD_SKILL");
            assertThat(s.get("after").asText()).isEqualTo("gRPC");
            assertThat(s.get("requiresConfirmation").asBoolean()).isTrue();
            assertThat(s.get("confirmationLabel").asText()).isEqualTo("Add only if you have this experience");
        });
        List<JsonNode> rewrites = suggestions.stream().filter(s -> s.get("type").asText().equals("BULLET_REWRITE")).toList();
        assertThat(rewrites).hasSize(2);   // the "Mentored 5 engineers" rewrite invented a number
        assertThat(rewrites).noneMatch(s -> s.get("after").asText().contains("5 engineers"));
        assertThat(suggestions).filteredOn(s -> s.get("applicable").asBoolean())
                .allSatisfy(s -> assertThat(s.get("impact").asInt()).as("never recommends a worse match")
                        .isGreaterThanOrEqualTo(0));
        assertThat(suggestions.getFirst().get("priority").asText()).isEqualTo("HIGH");

        int score = r.get("score").asInt();
        assertThat(r.get("projectedScore").asInt()).isGreaterThan(score);
        assertThat(r.get("projectedScoreWithConfirmed").asInt()).isGreaterThanOrEqualTo(r.get("projectedScore").asInt());
        ids.put("score", String.valueOf(score));

        // Same JD and resume again: the stored report, not counted, no LLM call
        int parsesBefore = jdParseCalls.get();
        JsonNode again = json(post("/api/analyses", Map.of("jdText", Fixtures.backendJd()), TOKEN), 201);
        assertThat(again.get("reused").asBoolean()).isTrue();
        assertThat(again.get("id").asText()).isEqualTo(ids.get("analysis"));
        assertThat(jdParseCalls.get()).isEqualTo(parsesBefore);
        assertThat(json(get("/api/analyses/usage", TOKEN), 200).get("used").asInt()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void generatesATailoredResumeOnlyWithConfirmedClaims() throws Exception {
        JsonNode report = json(get("/api/analyses/" + ids.get("analysis"), TOKEN), 200);
        List<JsonNode> applicable = list(report.get("suggestions")).stream()
                .filter(s -> s.get("applicable").asBoolean()).toList();
        JsonNode addGrpc = applicable.stream().filter(s -> s.get("after").asText().equals("gRPC")).findFirst().orElseThrow();

        List<Map<String, Object>> decisions = new ArrayList<>();
        for (JsonNode s : applicable) {
            boolean isClaim = s.get("requiresConfirmation").asBoolean();
            boolean accept = !isClaim || s == addGrpc;
            decisions.add(Map.of("suggestionId", s.get("id").asText(), "accepted", accept, "confirmed", false));
        }
        ResponseEntity<String> refused = post("/api/analyses/" + ids.get("analysis") + "/resume",
                Map.of("decisions", decisions, "format", "PDF"), TOKEN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("CONFIRMATION_REQUIRED").contains("Add gRPC");

        decisions.replaceAll(d -> d.get("suggestionId").equals(addGrpc.get("id").asText())
                ? Map.of("suggestionId", d.get("suggestionId"), "accepted", true, "confirmed", true) : d);
        JsonNode v = json(post("/api/analyses/" + ids.get("analysis") + "/resume",
                Map.of("decisions", decisions, "format", "PDF", "name", "Northwind backend"), TOKEN), 201);
        ids.put("version", v.get("id").asText());
        assertThat(v.get("scoreBefore").asInt()).isEqualTo(Integer.parseInt(ids.get("score")));
        assertThat(v.get("scoreAfter").asInt()).isGreaterThan(v.get("scoreBefore").asInt());
        assertThat(v.get("fileFormat").asText()).isEqualTo("PDF");

        JsonNode event = awaitEvent();
        assertThat(event.get("resumeVersionId").asText()).isEqualTo(ids.get("version"));
        assertThat(event.get("analysisId").asText()).isEqualTo(ids.get("analysis"));
        assertThat(event.get("scoreAfter").asInt()).isEqualTo(v.get("scoreAfter").asInt());

        ResponseEntity<byte[]> pdf = download(ids.get("version"), null);
        assertThat(pdf.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(pdf.getHeaders().getContentDisposition().getFilename()).isEqualTo("Asha_Rao_Northwind_backend_Resume.pdf");
        String pdfText = text(pdf.getBody());
        assertThat(pdfText).contains("gRPC").contains("Kubernetes (K8s)")
                .contains("Reduced payouts API p99 latency by 40% with Redis caching")
                .doesNotContain("Terraform").doesNotContain("event-driven");   // unconfirmed claims stayed out
        String docx = text(download(ids.get("version"), "DOCX").getBody());
        assertThat(docx).contains("gRPC").contains("EXPERIENCE");

        JsonNode detail = json(get("/api/resume-versions/" + ids.get("version"), TOKEN), 200);
        assertThat(detail.at("/content/skills/0").asText()).isEqualTo("Java");
        assertThat(json(get("/api/resume-versions", TOKEN), 200)).hasSize(1);
    }

    @Test
    @Order(3)
    void theTailoredVersionScoresHigherWhenReanalysed() throws Exception {
        JsonNode r = json(post("/api/analyses", Map.of("jdText", Fixtures.backendJd(),
                "resume", Map.of("source", "VERSION", "id", ids.get("version"))), TOKEN), 201);
        assertThat(r.get("reused").asBoolean()).isFalse();
        assertThat(r.get("score").asInt()).isGreaterThan(Integer.parseInt(ids.get("score")));
        assertThat(jdParseCalls.get()).as("JD parse served from the cache").isEqualTo(1);
        assertThat(jdbc.queryForObject("select hit_count from parsed_jds", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    void analysesAReferBoardJobAndAnUploadedFile() throws Exception {
        JsonNode fromJob = json(post("/api/analyses", Map.of("jobId", JOB.toString()), TOKEN), 201);
        assertThat(fromJob.at("/job/jobId").asText()).isEqualTo(JOB.toString());
        assertThat(fromJob.at("/job/title").asText()).isEqualTo("Senior Backend Engineer");
        assertThat(fromJob.at("/job/jdSource").asText()).isEqualTo("JOB");
        assertThat(json(get("/api/analyses?jobId=" + JOB, TOKEN), 200).get("total").asInt()).isEqualTo(1);

        String txt = Fixtures.backendJd().replace("Northwind Payments", "Kestrel Pay");
        JsonNode upload = json(upload("kestrel.txt", txt.getBytes(), MediaType.TEXT_PLAIN, TOKEN), 201);
        assertThat(upload.at("/job/jdSource").asText()).isEqualTo("UPLOAD");

        ResponseEntity<String> png = upload("jd.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0},
                MediaType.IMAGE_PNG, TOKEN);
        assertThat(png.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/api/analyses", Map.of("jdText", "Java dev wanted"), TOKEN).getBody()).contains("JD_TOO_SHORT");
    }

    @Test
    @Order(5)
    void freeTierLimitAndUnlimitedRoles() throws Exception {
        JsonNode usage = json(get("/api/analyses/usage", TOKEN), 200);
        assertThat(usage.get("used").asInt()).isEqualTo(4);
        assertThat(usage.get("remaining").asInt()).isZero();
        ResponseEntity<String> limited = post("/api/analyses",
                Map.of("jdText", Fixtures.backendJd().replace("Northwind", "Banyan")), TOKEN);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody()).contains("ANALYSIS_LIMIT").contains("reset on");

        assertThat(json(get("/api/analyses/usage", ADMIN), 200).get("unlimited").asBoolean()).isTrue();
        assertThat(post("/api/analyses", Map.of("jdText", Fixtures.backendJd().replace("Northwind", "Banyan")), ADMIN)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @Order(6)
    void worksWithoutTheLlm() throws Exception {
        llmDown.set(true);
        JsonNode r = json(post("/api/analyses", Map.of("jdText", Fixtures.backendJd().replace("Northwind", "Quill")),
                ADMIN), 201);
        assertThat(r.get("jdParser").asText()).isEqualTo("HEURISTIC");
        assertThat(r.get("aiSuggestions").asBoolean()).isFalse();
        assertThat(r.get("notices").toString()).contains("read without AI").contains("unavailable right now");
        assertThat(list(r.get("suggestions"))).noneMatch(s -> s.get("type").asText().equals("BULLET_REWRITE"))
                .anyMatch(s -> s.get("type").asText().equals("SKILLS_REORDER"));
        assertThat(r.get("score").asInt()).isBetween(40, 95);
        llmDown.set(false);
    }

    @Test
    @Order(7)
    void isolationAndAuth() {
        String other = TestJwt.tokenFor(UUID.randomUUID());
        assertThat(get("/api/analyses/" + ids.get("analysis"), other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/resume-versions/" + ids.get("version"), other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/analyses", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------------------------------------------

    static String parsedResume() throws IOException {
        ResumeContent c = Fixtures.resume();
        List<Map<String, Object>> exps = new ArrayList<>();
        for (ResumeContent.Experience e : c.experiences()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("company", e.company());
            m.put("title", e.title());
            m.put("location", e.location());
            m.put("startDate", e.startDate());
            m.put("endDate", e.endDate());
            m.put("isCurrent", e.current());
            m.put("bullets", e.bullets());
            exps.add(m);
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("fullName", c.fullName());
        parsed.put("email", c.email());
        parsed.put("phone", c.phone());
        parsed.put("location", c.location());
        parsed.put("headline", c.headline());
        parsed.put("skills", c.skills());
        parsed.put("experiences", exps);
        parsed.put("educations", List.of(Map.of("institution", "IIT Madras", "degree", "B.Tech",
                "fieldOfStudy", "Computer Science", "endYear", 2018)));
        return MAPPER.writeValueAsString(Map.of("resumeId", RESUME.toString(), "parser", "LLM", "parsed", parsed,
                "text", c.toPlainText()));
    }

    static Map<String, Object> jdParse() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Senior Backend Engineer (Payments)");
        m.put("company", "Northwind Payments");
        m.put("seniority", "SENIOR");
        m.put("minYears", 5);
        m.put("maxYears", null);
        m.put("requiredSkills", List.of("Java", "Spring Boot", "Kafka", "PostgreSQL", "Kubernetes", "Docker", "gRPC",
                "Rust"));
        m.put("preferredSkills", List.of("Go", "AWS", "Terraform"));
        m.put("responsibilities", List.of("Design and build high-throughput payment services in Java and Spring Boot",
                "Own event-driven microservices on Kafka", "Mentor engineers and lead code review"));
        m.put("keywords", List.of("payments", "microservices", "on-call", "blockchain"));
        m.put("education", Map.of("level", "BACHELORS", "fields", List.of("Computer Science"), "mandatory", false));
        m.put("location", "Bengaluru");
        m.put("remote", false);
        return m;
    }

    static Map<String, Object> suggestions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bulletRewrites", List.of(
                Map.of("experienceIndex", 0, "bulletIndex", 1,
                        "rewritten", "Reduced payouts API p99 latency by 40% with Redis caching", "reason", "Result first"),
                Map.of("experienceIndex", 0, "bulletIndex", 0, "rewritten", "Built event-driven Spring Boot services "
                        + "in Java on Kafka for UPI settlements processing 2M transactions a day", "reason", "JD terms"),
                Map.of("experienceIndex", 0, "bulletIndex", 3, "rewritten", "Mentored 5 engineers", "reason", "x")));
        m.put("summary", null);
        m.put("gapAdvice", "gRPC is required; add it only if you've used it.");
        m.put("seniorityAdvice", null);
        return m;
    }

    static String toolUse(String name, Map<String, Object> input) throws IOException {
        return MAPPER.writeValueAsString(Map.of("id", "msg", "type", "message", "role", "assistant", "model", "claude-test",
                "stop_reason", "tool_use", "content", List.of(Map.of("type", "tool_use", "id", "t1", "name", name,
                        "input", input)), "usage", Map.of("input_tokens", 1, "output_tokens", 1)));
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    private ResponseEntity<byte[]> download(String versionId, String format) {
        return rest.exchange("/api/resume-versions/" + versionId + "/file" + (format == null ? "" : "?format=" + format),
                HttpMethod.GET, new HttpEntity<>(headers(TOKEN)), byte[].class);
    }

    private ResponseEntity<String> upload(String name, byte[] bytes, MediaType type, String token) {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(type);
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        }, part));
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange("/api/analyses/upload", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private JsonNode json(ResponseEntity<String> r, int expected) throws Exception {
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(expected);
        return mapper.readTree(r.getBody());
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static Map<String, JsonNode> byKey(JsonNode array, String key) {
        Map<String, JsonNode> out = new HashMap<>();
        array.forEach(n -> out.put(n.get(key).asText(), n));
        return out;
    }

    private static String text(byte[] bytes) throws Exception {
        BodyContentHandler h = new BodyContentHandler(-1);
        new AutoDetectParser().parse(new ByteArrayInputStream(bytes), h, new org.apache.tika.metadata.Metadata(),
                new ParseContext());
        return h.toString();
    }

    private JsonNode awaitEvent() throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(300))) {
                return mapper.readTree(r.value());
            }
        }
        throw new AssertionError("No ResumeVersionCreated event");
    }
}
