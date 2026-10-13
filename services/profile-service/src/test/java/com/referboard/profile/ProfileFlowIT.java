package com.referboard.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.events.ProfileUpdatedEvent;
import com.referboard.events.Topics;
import com.referboard.profile.testdata.Documents;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestPostgres;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL + pgvector (Flyway), embedded Kafka, real PDF parsing; no LLM key, so the heuristic parser and
 * hashing embeddings run — exactly what a developer gets with docker compose and an empty .env.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.ai.llm.provider=none",
        "referboard.ai.embedding.provider=hashing",
        "referboard.storage.local-dir=target/it-storage"
})
@EmbeddedKafka(partitions = 1, topics = Topics.PROFILE_UPDATED, kraft = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ProfileFlowIT {

    @TestConfiguration
    static class Jwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, "profile");
    }

    private static Consumer<String, String> consumer;
    private static final List<ProfileUpdatedEvent> received = new ArrayList<>();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void subscribe(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("profile-it-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, Topics.PROFILE_UPDATED);
    }

    @AfterAll
    static void close() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void resumeUploadBuildsProfileAndPublishesEmbedding() throws Exception {
        UUID user = UUID.randomUUID();
        String token = TestJwt.tokenFor(user);

        // empty profile before onboarding
        JsonNode empty = json(get("/api/profile", token));
        assertThat(empty.get("exists").asBoolean()).isFalse();

        // upload → 202, parsing in background
        byte[] pdf = Documents.pdf(Documents.sampleResumeText());
        ResponseEntity<String> upload = upload(token, pdf, "Priya Sharma CV.pdf");
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode resume = json(upload);
        String resumeId = resume.get("id").asText();
        assertThat(resume.get("primary").asBoolean()).as("first resume is primary").isTrue();
        assertThat(resume.get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(resume.get("createdAt").isNull()).as("response reflects the saved row").isFalse();

        JsonNode parsedResume = await(() -> json(get("/api/profile/resumes/" + resumeId, token)),
                r -> !r.get("parseStatus").asText().equals("PENDING"));
        assertThat(parsedResume.get("parseStatus").asText()).isEqualTo("PARSED");
        assertThat(parsedResume.get("parser").asText()).isEqualTo("HEURISTIC");

        // profile was built from the resume
        JsonNode profile = await(() -> json(get("/api/profile", token)), p -> p.get("embeddingReady").asBoolean());
        assertThat(profile.get("fullName").asText()).isEqualTo("Priya Sharma");
        assertThat(profile.get("currentTitle").asText()).isEqualTo("Senior Software Engineer");
        assertThat(profile.get("currentCompany").asText()).isEqualTo("Razorpay");
        assertThat(profile.get("college").asText()).isEqualTo("Indian Institute of Technology, Roorkee");
        assertThat(profile.get("totalExperienceYears").decimalValue()).isGreaterThan(new java.math.BigDecimal("5.9"));
        assertThat(profile.get("experiences")).hasSize(2);
        assertThat(profile.get("parseSource").asText()).isEqualTo("HEURISTIC");
        JsonNode kafkaSkill = find(profile.get("skills"), "name", "kafka");
        assertThat(kafkaSkill.get("displayName").asText()).isEqualTo("Apache Kafka");
        assertThat(kafkaSkill.get("evidence").asText()).isEqualTo("BULLET");
        assertThat(find(profile.get("skills"), "name", "python").get("evidence").asText())
                .as("listed but never used in a bullet").isEqualTo("SKILLS_LIST");

        // embedding stored in pgvector, ProfileUpdated on Kafka
        Integer dims = jdbc.queryForObject("select vector_dims(embedding) from profiles where user_id = ?",
                Integer.class, user);
        assertThat(dims).isEqualTo(1536);
        ProfileUpdatedEvent event = awaitEvent(e -> e.userId().equals(user));
        assertThat(event.skills()).contains("java", "spring boot", "kafka", "kubernetes");
        assertThat(event.embedding()).hasSize(1536);
        assertThat(event.embeddingModel()).isEqualTo("hashing-v1:1536");
        assertThat(event.pastCompanies()).containsExactly("Razorpay", "Flipkart");

        // parsed endpoint for the analyzer
        JsonNode parsed = json(get("/api/profile/resumes/" + resumeId + "/parsed", token));
        assertThat(parsed.get("text").asText()).contains("Razorpay");
        assertThat(parsed.at("/parsed/experiences/0/bullets")).hasSize(3);

        // the original file can be downloaded back unchanged
        ResponseEntity<byte[]> file = rest.exchange("/api/profile/resumes/" + resumeId + "/file", HttpMethod.GET,
                new HttpEntity<>(auth(token)), byte[].class);
        assertThat(file.getBody()).isEqualTo(pdf);
        assertThat(file.getHeaders().getContentDisposition().getFilename()).isEqualTo("Priya Sharma CV.pdf");

        // identical re-upload is idempotent
        ResponseEntity<String> again = upload(token, pdf, "copy.pdf");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(again).get("id").asText()).isEqualTo(resumeId);
    }

    @Test
    void userEditsAndPreferencesPublishNewVersionsAndUserSkillsSurviveReparse() throws Exception {
        UUID user = UUID.randomUUID();
        String token = TestJwt.tokenFor(user);
        String resumeId = json(upload(token, Documents.docx(Documents.sampleResumeText()), "cv.docx"))
                .get("id").asText();
        JsonNode profile = await(() -> json(get("/api/profile", token)), p -> p.get("exists").asBoolean()
                && p.get("experiences").size() == 2);

        // user reviews: adds a skill that isn't on the resume, keeps the rest
        Map<String, Object> edit = Map.of(
                "fullName", "Priya S.",
                "headline", "Backend engineer · payments",
                "skills", List.of(Map.of("name", "Java", "years", 6), Map.of("name", "Golang")),
                "experiences", mapper.convertValue(profile.get("experiences"), List.class),
                "educations", mapper.convertValue(profile.get("educations"), List.class));
        JsonNode saved = json(put("/api/profile", token, edit));
        assertThat(saved.get("fullName").asText()).isEqualTo("Priya S.");
        assertThat(find(saved.get("skills"), "name", "java").get("source").asText()).isEqualTo("PARSED");
        assertThat(find(saved.get("skills"), "name", "go").get("source").asText()).isEqualTo("USER");
        long editedVersion = saved.get("profileVersion").asLong();

        // preferences
        JsonNode prefs = json(put("/api/profile/preferences", token, Map.of(
                "roles", List.of("Backend Engineer", "SDE 2"), "locations", List.of("Bengaluru", "Remote"),
                "remoteOnly", false, "openToRemote", true, "minSalaryInr", 3_500_000, "experienceLevel", "SENIOR")));
        assertThat(prefs.get("roles")).hasSize(2);
        ProfileUpdatedEvent prefsEvent = awaitEvent(e -> e.userId().equals(user) && e.profileVersion() > editedVersion);
        assertThat(prefsEvent.preferences().roles()).containsExactly("Backend Engineer", "SDE 2");
        assertThat(prefsEvent.preferences().minSalaryInr()).isEqualTo(3_500_000L);
        assertThat(prefsEvent.preferences().experienceLevel()).isEqualTo("SENIOR");

        // re-parse rebuilds resume-derived data but keeps the user's own skill
        assertThat(post("/api/profile/resumes/" + resumeId + "/reparse", token).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        JsonNode reparsed = await(() -> json(get("/api/profile", token)),
                p -> p.get("profileVersion").asLong() > prefsEvent.profileVersion());
        assertThat(find(reparsed.get("skills"), "name", "go").get("source").asText()).isEqualTo("USER");
        assertThat(find(reparsed.get("skills"), "name", "kafka")).isNotNull();
    }

    @Test
    void validationOwnershipAndAuth() throws Exception {
        UUID owner = UUID.randomUUID();
        String ownerToken = TestJwt.tokenFor(owner);
        String resumeId = json(upload(ownerToken, Documents.pdf(Documents.sampleResumeText()), "a.pdf"))
                .get("id").asText();

        // someone else's resume is indistinguishable from a missing one
        String intruder = TestJwt.tokenFor(UUID.randomUUID());
        assertThat(get("/api/profile/resumes/" + resumeId, intruder).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/profile/resumes/" + resumeId, HttpMethod.DELETE,
                new HttpEntity<>(auth(intruder)), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // bad experience dates
        ResponseEntity<String> bad = put("/api/profile", ownerToken, Map.of("skills", List.of(),
                "educations", List.of(),
                "experiences", List.of(Map.of("company", "X", "title", "Y", "startDate", "2024-05",
                        "endDate", "2023-01", "current", false))));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("VALIDATION_FAILED").contains("endDate must be on or after startDate");

        // not a document
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        ResponseEntity<String> image = upload(ownerToken, png, "resume.pdf");
        assertThat(image.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(image.getBody()).contains("UNSUPPORTED_FILE_TYPE");

        // tokens: none, expired, wrong audience
        assertThat(get("/api/profile", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/profile", TestJwt.token(owner, TestJwt.ISSUER, TestJwt.AUDIENCE,
                Instant.now().minusSeconds(120))).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/profile", TestJwt.token(owner, TestJwt.ISSUER, "some-other-api",
                Instant.now().plusSeconds(600))).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // delete removes the record and the stored file
        String key = jdbc.queryForObject("select storage_key from resumes where id = ?", String.class,
                UUID.fromString(resumeId));
        await(() -> json(get("/api/profile/resumes/" + resumeId, ownerToken)),
                r -> !r.get("parseStatus").asText().equals("PENDING"));
        assertThat(rest.exchange("/api/profile/resumes/" + resumeId, HttpMethod.DELETE,
                new HttpEntity<>(auth(ownerToken)), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/profile/resumes/" + resumeId, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("target/it-storage", key))).isFalse();
    }

    @Test
    void swaggerAndHealthArePublic() {
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getBody()).contains("/api/profile/resumes");
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- helpers ----------

    private ProfileUpdatedEvent awaitEvent(Predicate<ProfileUpdatedEvent> match) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (received) {
                for (ProfileUpdatedEvent e : received) {
                    if (match.test(e)) {
                        return e;
                    }
                }
                for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500))) {
                    ProfileUpdatedEvent e = mapper.readValue(r.value(), ProfileUpdatedEvent.class);
                    assertThat(r.key()).isEqualTo(e.userId().toString());
                    received.add(e);
                }
            }
        }
        throw new AssertionError("Expected ProfileUpdated event not received");
    }

    private interface Fetch {
        JsonNode get() throws Exception;
    }

    private static JsonNode await(Fetch fetch, Predicate<JsonNode> done) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = fetch.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Condition not reached; last value: " + last);
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asText())) {
                return n;
            }
        }
        throw new AssertionError("No element with " + field + "=" + value + " in " + array);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        return mapper.readTree(response.getBody());
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), String.class);
    }

    private ResponseEntity<String> post(String path, String token) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(auth(token)), String.class);
    }

    private ResponseEntity<String> put(String path, String token, Object body) {
        HttpHeaders h = auth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> upload(String token, byte[] content, String name) {
        HttpHeaders h = auth(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        return rest.exchange("/api/profile/resumes", HttpMethod.POST, new HttpEntity<>(form, h), String.class);
    }
}
