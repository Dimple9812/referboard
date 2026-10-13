package com.referboard.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.events.DomainEvent;
import com.referboard.events.EventMetadata;
import com.referboard.events.JobClosedEvent;
import com.referboard.events.ReferralStatusChangedEvent;
import com.referboard.events.ResumeVersionCreatedEvent;
import com.referboard.events.Topics;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestPostgres;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tracker-service against real PostgreSQL and embedded Kafka; job-service is a MockWebServer. Drives the board
 * through HTTP and through the events it consumes (referral progress, tailored resumes, closed postings).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200"
})
@EmbeddedKafka(partitions = 1, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.REFERRAL_STATUS_CHANGED, Topics.RESUME_VERSION_CREATED, Topics.JOB_CLOSED,
                Topics.APPLICATION_STATUS_CHANGED, Topics.REFERRAL_STATUS_CHANGED + ".DLT"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackerFlowIT {

    static final UUID USER = UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final UUID JOB = UUID.randomUUID();        // added by the user
    static final UUID JOB2 = UUID.randomUUID();       // appears through referral outreach
    static final UUID JOB3 = UUID.randomUUID();       // tailored resume arrives before the card
    static final UUID JOB4 = UUID.randomUUID();       // closed before the card
    static final UUID JOB5 = UUID.randomUUID();       // user and event race to create it
    static final UUID MISSING = UUID.randomUUID();

    static MockWebServer jobService;
    static Consumer<String, String> consumer;
    static final List<ConsumerRecord<String, String>> received = new ArrayList<>();
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
        TestPostgres.register(registry, "tracker");
        jobService = new MockWebServer();
        jobService.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.startsWith("/api/jobs/") && !path.endsWith(MISSING.toString())) {
                    UUID id = UUID.fromString(path.substring("/api/jobs/".length()));
                    {
                        return new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                                {"job": {"id": "%s", "title": "Backend Engineer %s", "companyName": "Northwind",
                                 "location": "Bengaluru", "applyUrl": "https://example.com/jobs/%s", "skills": []},
                                 "descriptionHtml": "<p>x</p>", "status": "OPEN"}
                                """.formatted(id, id.toString().substring(0, 4), id));
                    }
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        jobService.start();
        registry.add("referboard.clients.job-service-url", () -> "http://localhost:" + jobService.getPort());
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void subscribe(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("tracker-it-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromEmbeddedTopics(consumer, Topics.APPLICATION_STATUS_CHANGED);
    }

    @AfterAll
    static void close() throws IOException {
        consumer.close();
        jobService.shutdown();
    }

    @Test
    @Order(1)
    void addsReferBoardAndExternalJobs() throws Exception {
        JsonNode card = json(call(HttpMethod.POST, "/api/applications", Map.of("jobId", JOB.toString()), TOKEN), 201);
        ids.put("job", card.get("id").asText());
        assertThat(card.get("stage").asText()).isEqualTo("SAVED");
        assertThat(card.get("companyName").asText()).isEqualTo("Northwind");
        assertThat(card.get("applyUrl").asText()).isEqualTo("https://example.com/jobs/" + JOB);
        assertThat(jobService.takeRequest().getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);

        ResponseEntity<String> dup = call(HttpMethod.POST, "/api/applications", Map.of("jobId", JOB.toString()), TOKEN);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).contains("ALREADY_TRACKED").contains(ids.get("job"));
        assertThat(call(HttpMethod.POST, "/api/applications", Map.of("jobId", MISSING.toString()), TOKEN)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/applications", Map.of("jobTitle", "SDE"), TOKEN).getBody())
                .contains("JOB_REQUIRED");

        JsonNode ext = json(call(HttpMethod.POST, "/api/applications", Map.of("jobTitle", "Platform Engineer",
                "companyName", "Kestrel (via careers page)", "stage", "APPLIED"), TOKEN), 201);
        ids.put("ext", ext.get("id").asText());
        assertThat(ext.get("jobId").isNull()).isTrue();
        assertThat(ext.get("appliedAt").isNull()).as("adding straight to Applied records the date").isFalse();

        JsonNode board = json(call(HttpMethod.GET, "/api/applications/board", null, TOKEN), 200);
        assertThat(board.get("total").asInt()).isEqualTo(2);
        assertThat(board.at("/columns/0/stage").asText()).isEqualTo("SAVED");
        assertThat(board.at("/columns/0/count").asInt()).isEqualTo(1);
        assertThat(board.at("/columns/1/label").asText()).isEqualTo("Applied");
        assertThat(board.get("columns")).hasSize(7);

        List<JsonNode> published = awaitEvents(2);
        assertThat(published.get(0).get("newStage").asText()).isEqualTo("SAVED");
        assertThat(published.get(0).get("previousStage").isNull()).isTrue();
    }

    @Test
    @Order(2)
    void dragAndDropMovesAndReorders() throws Exception {
        // Two more saved cards so ordering matters
        String a = json(call(HttpMethod.POST, "/api/applications", Map.of("jobTitle", "A", "companyName", "Co"),
                TOKEN), 201).get("id").asText();
        String b = json(call(HttpMethod.POST, "/api/applications", Map.of("jobTitle", "B", "companyName", "Co"),
                TOKEN), 201).get("id").asText();
        assertThat(savedOrder()).as("new cards go on top").containsExactly(b, a, ids.get("job"));

        json(call(HttpMethod.POST, "/api/applications/" + b + "/move", Map.of("stage", "SAVED", "index", 2), TOKEN), 200);
        assertThat(savedOrder()).containsExactly(a, ids.get("job"), b);
        json(call(HttpMethod.POST, "/api/applications/" + b + "/move", Map.of("stage", "SAVED", "index", 1), TOKEN), 200);
        assertThat(savedOrder()).containsExactly(a, b, ids.get("job"));

        JsonNode moved = json(call(HttpMethod.POST, "/api/applications/" + ids.get("job") + "/move",
                Map.of("stage", "APPLIED", "index", 1), TOKEN), 200);
        assertThat(moved.get("stage").asText()).isEqualTo("APPLIED");
        assertThat(moved.get("appliedAt").isNull()).isFalse();
        JsonNode applied = json(call(HttpMethod.GET, "/api/applications/board", null, TOKEN), 200).at("/columns/1/cards");
        assertThat(applied.get(0).get("id").asText()).isEqualTo(ids.get("ext"));
        assertThat(applied.get(1).get("id").asText()).isEqualTo(ids.get("job"));

        JsonNode ev = awaitEvents(5).get(4);
        assertThat(ev.get("previousStage").asText()).isEqualTo("SAVED");
        assertThat(ev.get("newStage").asText()).isEqualTo("APPLIED");
        assertThat(ev.get("jobId").asText()).isEqualTo(JOB.toString());

        json(call(HttpMethod.DELETE, "/api/applications/" + a, null, TOKEN), 204);
        json(call(HttpMethod.DELETE, "/api/applications/" + b, null, TOKEN), 204);
    }

    @Test
    @Order(3)
    void referralProgressCreatesAndAdvancesCards() throws Exception {
        UUID contact = UUID.randomUUID();
        ReferralStatusChangedEvent sent = referral(JOB2, contact, "Rohan Mehta", "NOT_CONTACTED", "REQUEST_SENT");
        send(sent);
        send(sent);   // redelivery
        JsonNode card = await(() -> byJob(JOB2), "card created from referral outreach");
        assertThat(card.get("stage").asText()).isEqualTo("SAVED");
        assertThat(card.get("source").asText()).isEqualTo("REFERRAL");
        assertThat(card.get("jobTitle").asText()).isEqualTo("Staff Engineer");
        assertThat(card.get("referralSummary").asText()).isEqualTo("Connection request sent to Rohan Mehta");
        ids.put("job2", card.get("id").asText());

        send(referral(JOB2, contact, "Rohan Mehta", "CONNECTED", "REFERRAL_ASKED"));
        await(() -> stageOf(JOB2, "REFERRAL_REQUESTED"), "advanced to Referral requested");

        // Card already past the referral stages: the update is recorded but the column doesn't change
        json(call(HttpMethod.POST, "/api/applications/" + ids.get("job") + "/move", Map.of("stage", "INTERVIEW"),
                TOKEN), 200);
        send(referral(JOB, UUID.randomUUID(), "Meera Iyer", "REFERRAL_ASKED", "REFERRED"));
        JsonNode jobCard = await(() -> {
            JsonNode c = byJob(JOB);
            return c != null && "Referred by Meera Iyer".equals(c.path("referralSummary").asText()) ? c : null;
        }, "referral recorded on the interview card");
        assertThat(jobCard.get("stage").asText()).isEqualTo("INTERVIEW");

        JsonNode detail = json(call(HttpMethod.GET, "/api/applications/" + ids.get("job2"), null, TOKEN), 200);
        List<String> types = new ArrayList<>();
        detail.get("timeline").forEach(t -> types.add(t.get("type").asText()));
        assertThat(types).containsExactly("CREATED", "REFERRAL_UPDATE", "REFERRAL_UPDATE", "STAGE_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Long.class,
                sent.metadata().eventId())).isEqualTo(1);

        // A referral status that doesn't mean outreach doesn't put a job on the board
        UUID untouched = UUID.randomUUID();
        send(referral(untouched, UUID.randomUUID(), "X", "REQUEST_SENT", "NOT_CONTACTED"));
        send(referral(JOB2, contact, "Rohan Mehta", "REFERRAL_ASKED", "REFERRED"));   // marker: processed after
        await(() -> stageOf(JOB2, "REFERRED"), "advanced to Referred");
        assertThat(call(HttpMethod.GET, "/api/applications/by-job/" + untouched, null, TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(4)
    void linksTailoredResumesAndFlagsClosedPostings() throws Exception {
        UUID v1 = UUID.randomUUID();
        send(new ResumeVersionCreatedEvent(meta("ResumeVersionCreated"), USER, v1, "Northwind backend", JOB, UUID.randomUUID(),
                58, 81, "DOCX"));
        await(() -> {
            JsonNode c = byJob(JOB);
            return c != null && v1.toString().equals(c.path("resumeVersionId").asText()) ? c : null;
        }, "resume linked to existing card");

        UUID v2 = UUID.randomUUID();
        send(new ResumeVersionCreatedEvent(meta("ResumeVersionCreated"), USER, v2, "Early bird", JOB3, UUID.randomUUID(),
                40, 70, "PDF"));
        send(new JobClosedEvent(meta("JobClosed"), JOB4, Instant.parse("2026-10-01T00:00:00Z"), "UNSEEN_7_DAYS"));
        send(new JobClosedEvent(meta("JobClosed"), JOB2, Instant.parse("2026-10-02T00:00:00Z"), "UNSEEN_7_DAYS"));
        await(() -> {
            JsonNode c = byJob(JOB2);
            return c != null && c.get("jobClosed").asBoolean() ? c : null;
        }, "closed posting flagged");
        await(() -> jdbc.queryForObject("select count(*) from resume_links_pending", Long.class) == 1
                && jdbc.queryForObject("select count(*) from closed_jobs", Long.class) == 2 ? true : null,
                "pending link and closed job stored");

        JsonNode c3 = json(call(HttpMethod.POST, "/api/applications", Map.of("jobId", JOB3.toString()), TOKEN), 201);
        assertThat(c3.get("resumeVersionId").asText()).as("pending resume picked up").isEqualTo(v2.toString());
        JsonNode c4 = json(call(HttpMethod.POST, "/api/applications", Map.of("jobId", JOB4.toString()), TOKEN), 201);
        assertThat(c4.get("jobClosed").asBoolean()).as("closed before the card existed").isTrue();
        assertThat(c4.get("jobClosedAt").asText()).isEqualTo("2026-10-01T00:00:00Z");

        JsonNode timeline = json(call(HttpMethod.GET, "/api/applications/" + c3.get("id").asText(), null, TOKEN), 200)
                .get("timeline");
        assertThat(timeline.get(1).get("type").asText()).isEqualTo("RESUME_LINKED");
        assertThat(timeline.get(1).get("message").asText()).isEqualTo("“Early bird” linked to this application");
    }

    @Test
    @Order(5)
    void userAndEventRacingForTheSameJobMakeOneCard() throws Exception {
        for (int round = 0; round < 6; round++) {
            UUID job = round == 0 ? JOB5 : UUID.randomUUID();
            String contact = "Anil " + round;
            CompletableFuture<ResponseEntity<String>> post = CompletableFuture.supplyAsync(() ->
                    call(HttpMethod.POST, "/api/applications", Map.of("jobId", job.toString()), TOKEN));
            send(referral(job, UUID.randomUUID(), contact, "NOT_CONTACTED", "REQUEST_SENT"));
            ResponseEntity<String> r = post.get();
            assertThat(r.getStatusCode().value()).as(r.getBody()).isIn(201, 409);
            await(() -> {
                JsonNode c = byJob(job);
                return c != null && c.path("referralSummary").asText().contains(contact) ? c : null;
            }, "referral recorded in round " + round);
            assertThat(jdbc.queryForObject("select count(*) from applications where job_id = ? and deleted_at is null",
                    Long.class, job)).isEqualTo(1);
        }
    }

    @Test
    @Order(6)
    void editsNotesDatesAndIsolation() throws Exception {
        String id = ids.get("job");
        JsonNode upd = json(call(HttpMethod.PATCH, "/api/applications/" + id, Map.of("notes", "Recruiter: Divya",
                "nextAction", "System design round", "nextActionAt", "2026-10-20T05:30:00Z"), TOKEN), 200);
        assertThat(upd.get("nextAction").asText()).isEqualTo("System design round");
        assertThat(call(HttpMethod.PATCH, "/api/applications/" + id, Map.of("companyName", "Other"), TOKEN).getBody())
                .contains("JOB_FIELDS_READ_ONLY");
        assertThat(call(HttpMethod.PATCH, "/api/applications/" + ids.get("ext"), Map.of("location", "Remote"), TOKEN)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode withNote = json(call(HttpMethod.POST, "/api/applications/" + id + "/notes",
                Map.of("text", "Prepared the payments case study"), TOKEN), 201);
        JsonNode last = withNote.get("timeline").get(withNote.get("timeline").size() - 1);
        assertThat(last.get("type").asText()).isEqualTo("NOTE");
        assertThat(withNote.get("timeline").toString()).contains("Updated notes, nextAction, nextActionAt");

        json(call(HttpMethod.PATCH, "/api/applications/" + id, Map.of("clearNextAction", true), TOKEN), 200);
        assertThat(byJob(JOB).get("nextAction").isNull()).isTrue();

        String other = TestJwt.tokenFor(UUID.randomUUID());
        assertThat(call(HttpMethod.GET, "/api/applications/" + id, null, other).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(call(HttpMethod.GET, "/api/applications/board", null, other), 200).get("total").asInt()).isZero();
        assertThat(call(HttpMethod.GET, "/api/applications/board", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        json(call(HttpMethod.DELETE, "/api/applications/" + id, null, TOKEN), 204);
        assertThat(call(HttpMethod.GET, "/api/applications/by-job/" + JOB, null, TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(call(HttpMethod.POST, "/api/applications", Map.of("jobId", JOB.toString()), TOKEN), 201)
                .get("stage").asText()).as("can be re-added after removal").isEqualTo("SAVED");
    }

    // ---------------------------------------------------------------------------------------------------------

    private List<String> savedOrder() throws Exception {
        List<String> out = new ArrayList<>();
        json(call(HttpMethod.GET, "/api/applications/board", null, TOKEN), 200).at("/columns/0/cards")
                .forEach(c -> out.add(c.get("id").asText()));
        return out;
    }

    private JsonNode byJob(UUID jobId) {
        ResponseEntity<String> r = call(HttpMethod.GET, "/api/applications/by-job/" + jobId, null, TOKEN);
        try {
            return r.getStatusCode().is2xxSuccessful() ? mapper.readTree(r.getBody()) : null;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode stageOf(UUID jobId, String stage) {
        JsonNode c = byJob(jobId);
        return c != null && stage.equals(c.get("stage").asText()) ? c : null;
    }

    private static ReferralStatusChangedEvent referral(UUID jobId, UUID contactId, String name, String from, String to) {
        return new ReferralStatusChangedEvent(meta("ReferralStatusChanged"), USER, jobId, "Staff Engineer", "Kestrel",
                "Remote - India", "https://example.com/apply", contactId, name, from, to, Instant.now());
    }

    private static EventMetadata meta(String type) {
        return new EventMetadata(UUID.randomUUID(), type, 1, Instant.now(), "test", "corr-" + UUID.randomUUID());
    }

    private void send(DomainEvent e) throws Exception {
        kafka.send(e.topic(), e.key(), mapper.writeValueAsString(e)).get();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode json(ResponseEntity<String> r, int expected) throws Exception {
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(expected);
        return r.getBody() == null ? null : mapper.readTree(r.getBody());
    }

    private <T> T await(java.util.function.Supplier<T> probe, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            T v = probe.get();
            if (v != null && !Boolean.FALSE.equals(v)) {
                return v;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private List<JsonNode> awaitEvents(int atLeast) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            KafkaTestUtils.getRecords(consumer, Duration.ofMillis(300)).forEach(received::add);
            if (received.size() >= atLeast) {
                List<JsonNode> out = new ArrayList<>();
                for (ConsumerRecord<String, String> r : received) {
                    out.add(mapper.readTree(r.value()));
                }
                return out;
            }
        }
        throw new AssertionError("Expected " + atLeast + " ApplicationStatusChanged events, got " + received.size());
    }
}
