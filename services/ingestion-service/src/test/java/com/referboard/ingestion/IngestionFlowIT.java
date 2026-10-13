package com.referboard.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.referboard.events.JobClosedEvent;
import com.referboard.events.JobUpsertedEvent;
import com.referboard.events.Topics;
import com.referboard.ingestion.domain.IngestionRun;
import com.referboard.ingestion.service.IngestionOrchestrator;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Four ingestion runs over ~two months of simulated time against MockWebServer stand-ins for Greenhouse, Lever,
 * Ashby and Adzuna, with real PostgreSQL and an embedded Kafka broker:
 * <ol>
 *   <li>first run: filters, cross-source de-dup, wrong slug → NOT_FOUND, events with embeddings</li>
 *   <li>same data an hour later: idempotent, no events</li>
 *   <li>+8 days: a job removed from a healthy board closes; a board that's down closes nothing; edits re-publish</li>
 *   <li>+61 days: still-listed jobs are flagged as possible ghost jobs</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.ai.embedding.provider=hashing",
        "referboard.ingestion.seed-file=classpath:test-companies.yml",
        "referboard.ingestion.retry-initial-backoff=PT0.01S",
        "referboard.ingestion.retry-max-attempts=2",
        "referboard.ingestion.requests-per-second-per-host=100",
        "referboard.ingestion.adzuna.app-id=test-id",
        "referboard.ingestion.adzuna.app-key=test-key",
        "referboard.ingestion.adzuna.queries[0]=backend developer",
        "referboard.ingestion.adzuna.pages-per-query=2",
        "referboard.ingestion.cron=-"
})
@EmbeddedKafka(partitions = 1, topics = {Topics.JOB_UPSERTED, Topics.JOB_CLOSED}, kraft = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class IngestionFlowIT {

    static final MockWebServer api = new MockWebServer();
    static final Map<String, String> bodies = new ConcurrentHashMap<>();
    static final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    static final Set<String> robotsFetched = ConcurrentHashMap.newKeySet();
    static final MutableClock clock = new MutableClock(Instant.parse("2026-10-13T06:00:00Z"));

    static {
        api.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/robots.txt")) {
                    robotsFetched.add(path);
                    return new MockResponse().setBody("User-agent: *\nDisallow: /admin\n");
                }
                String key = path.startsWith("/v1/api/jobs/in/search/") ? "adzuna-page-" + path.substring(path.lastIndexOf('/') + 1)
                        : path;
                Integer status = statuses.get(key);
                if (status != null) {
                    return new MockResponse().setResponseCode(status);
                }
                String body = bodies.get(key);
                return body == null ? new MockResponse().setResponseCode(404)
                        : new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        try {
            api.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock testClock() {
            return clock;
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, "ingestion");
        String base = api.url("/").toString().replaceAll("/$", "");
        registry.add("referboard.ingestion.greenhouse.base-url", () -> base);
        registry.add("referboard.ingestion.lever.base-url", () -> base);
        registry.add("referboard.ingestion.ashby.base-url", () -> base);
        registry.add("referboard.ingestion.adzuna.base-url", () -> base);
    }

    private static Consumer<String, String> consumer;
    private static final List<JobUpsertedEvent> upserts = new ArrayList<>();
    private static final List<JobClosedEvent> closes = new ArrayList<>();

    @Autowired
    IngestionOrchestrator orchestrator;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    TestRestTemplate rest;

    @BeforeAll
    static void setUp(@Autowired EmbeddedKafkaBroker broker) throws Exception {
        Map<String, Object> props = KafkaTestUtils.consumerProps("ingestion-it-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromEmbeddedTopics(consumer, Topics.JOB_UPSERTED, Topics.JOB_CLOSED);

        bodies.put("/v1/boards/acmepay/jobs", fixture("greenhouse-acmepay.json"));
        bodies.put("/v0/postings/brightdata", fixture("lever-brightdata.json"));
        bodies.put("/posting-api/job-board/nimbusai", fixture("ashby-nimbusai.json"));
        bodies.put("adzuna-page-1", fixture("adzuna-in.json"));
        bodies.put("/v0/postings/downco", """
                [{"id":"dc-1","text":"Backend Developer","categories":{"location":"Chennai","commitment":"Full-time"},
                  "createdAt":1790000000000,"description":"<p>Java and PostgreSQL.</p>","lists":[],
                  "hostedUrl":"https://jobs.lever.co/downco/dc-1"}]""");
    }

    @AfterAll
    static void tearDown() throws IOException {
        consumer.close();
        api.shutdown();
    }

    @Test
    void lifecycleAcrossRuns() throws Exception {
        // ---------- run 1: first sight of everything ----------
        IngestionRun run1 = orchestrator.run(IngestionRun.Trigger.MANUAL).orElseThrow();

        assertThat(run1.getStatus()).as("GhostCo 404s, so the run is PARTIAL, not FAILED").isEqualTo(IngestionRun.Status.PARTIAL);
        assertThat(run1.getSourcesFailed()).isEqualTo(1);
        assertThat(run1.getSourcesOk()).as("acmepay, brightdata, nimbusai, downco, adzuna query").isEqualTo(5);
        assertThat(run1.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.sourceKey()).isEqualTo("greenhouse:ghostco");
            assertThat(e.kind()).isEqualTo("NOT_FOUND");
        });
        assertThat(sourceStatus("greenhouse:ghostco")).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from sources where source_key = 'workday:nope'", Integer.class))
                .as("unsupported ATS in seed is skipped").isZero();
        assertThat(jdbc.queryForObject("select enabled from sources where source_key = 'ashby:paused'", Boolean.class))
                .isFalse();
        assertThat(robotsFetched).as("robots.txt checked before fetching").isNotEmpty();

        // kept: GH 4001 (Bengaluru) + 4002 (Remote India); Lever Pune data eng; Ashby ML (remote, India);
        // Adzuna Android (Hyderabad) + Adzuna copy of 4001 (merged); DownCo Chennai.
        // dropped: GH NYC + sales role, Lever US-remote SRE, Ashby unlisted, Adzuna anonymous company.
        assertThat(run1.getKept()).isEqualTo(7);
        Map<UUID, JobUpsertedEvent> latest = awaitUpserts(6);
        assertThat(latest).hasSize(6);
        assertThat(latest.values()).extracting(JobUpsertedEvent::title).containsExactlyInAnyOrder(
                "Senior Backend Engineer", "Frontend Engineer", "Data Engineer", "Machine Learning Engineer",
                "Android Developer", "Backend Developer");
        assertThat(latest.values()).allSatisfy(e -> {
            assertThat(e.embedding()).hasSize(1536);
            assertThat(e.embeddingModel()).isEqualTo("hashing-v1:1536");
            assertThat(e.ghostJobSuspected()).isFalse();
        });

        JobUpsertedEvent backend = byTitle(latest, "Senior Backend Engineer");
        assertThat(backend.source()).as("the ATS copy wins over Adzuna's snippet").isEqualTo("greenhouse");
        assertThat(backend.applyUrl()).isEqualTo("https://boards.greenhouse.io/acmepay/jobs/4001");
        assertThat(backend.skills()).contains("java", "spring boot", "kafka", "postgresql");
        assertThat(backend.seniority()).isEqualTo("SENIOR");
        assertThat(backend.descriptionHtml()).startsWith("<p>Build payment rails");
        assertThat(backend.jobId()).isEqualTo(UUID.nameUUIDFromBytes(backend.dedupKey().getBytes()));
        JobUpsertedEvent ml = byTitle(latest, "Machine Learning Engineer");
        assertThat(ml.remote()).isTrue();
        assertThat(ml.salaryMin()).isEqualTo(4_000_000L);

        // ---------- run 2: an hour later, nothing changed ----------
        clock.advance(Duration.ofHours(1));
        int eventsBefore = drain().size();
        IngestionRun run2 = orchestrator.run(IngestionRun.Trigger.MANUAL).orElseThrow();
        assertThat(run2.getCreated()).isZero();
        assertThat(run2.getUpdated()).isZero();
        assertThat(run2.getUnchanged()).isEqualTo(6);
        Thread.sleep(1500);
        assertThat(drain()).as("idempotent: no events for unchanged postings").hasSize(eventsBefore);

        // ---------- run 3: 8 days later ----------
        clock.advance(Duration.ofDays(8));
        ObjectNode acme = (ObjectNode) mapper.readTree(bodies.get("/v1/boards/acmepay/jobs"));
        ArrayNode jobs = (ArrayNode) acme.get("jobs");
        jobs.remove(1);                                                  // Frontend Engineer taken down
        ((ObjectNode) jobs.get(0)).put("content", "&lt;p&gt;Now also Redis and Go.&lt;/p&gt;"); // 4001 edited
        bodies.put("/v1/boards/acmepay/jobs", mapper.writeValueAsString(acme));
        statuses.put("/v0/postings/downco", 503);                        // DownCo's board is down

        IngestionRun run3 = orchestrator.run(IngestionRun.Trigger.MANUAL).orElseThrow();
        assertThat(run3.getClosed()).as("only the job removed from a healthy board").isEqualTo(1);
        List<JobClosedEvent> closed = awaitCloses(1);
        assertThat(closed).singleElement().satisfies(c -> {
            assertThat(c.jobId()).isEqualTo(byTitle(latest, "Frontend Engineer").jobId());
            assertThat(c.reason()).isEqualTo(JobClosedEvent.REASON_UNSEEN_7_DAYS);
        });
        UUID downcoJob = byTitle(latest, "Backend Developer").jobId();
        assertThat(isOpen(downcoJob)).as("board outage never closes jobs").isTrue();
        assertThat(sourceStatus("lever:downco")).isEqualTo("FAILING");

        JobUpsertedEvent edited = awaitUpsert(e -> e.jobId().equals(backend.jobId()) && e.skills().contains("redis"));
        assertThat(edited.embedding()).as("content changed → re-embedded").hasSize(1536);
        assertThat(edited.contentHash()).isNotEqualTo(backend.contentHash());

        // ---------- run 4: 61 days after first seen ----------
        clock.advance(Duration.ofDays(53));
        orchestrator.run(IngestionRun.Trigger.MANUAL).orElseThrow();
        JobUpsertedEvent ghost = awaitUpsert(e -> e.jobId().equals(backend.jobId()) && e.ghostJobSuspected());
        assertThat(ghost.embedding()).as("flag-only update carries no vector").isNull();
        assertThat(ghost.contentHash()).isEqualTo(edited.contentHash());
        assertThat(isOpen(downcoJob)).as("still open while its board stays down").isTrue();
        long deadline = System.currentTimeMillis() + 10_000;
        int pending;
        do {
            pending = jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Integer.class);
            Thread.sleep(100);
        } while (pending > 0 && System.currentTimeMillis() < deadline);
        assertThat(pending).as("every event relayed to Kafka").isZero();
        assertThat(jdbc.queryForList("select title from ingested_postings where ghost_job_suspected", String.class))
                .as("all 4 still-listed jobs from healthy boards flagged (DownCo's board is down, so not re-seen)")
                .containsExactlyInAnyOrder("Senior Backend Engineer", "Data Engineer", "Machine Learning Engineer",
                        "Android Developer");
        long eventDeadline = System.currentTimeMillis() + 10_000;
        long ghostEvents = 0;
        while (ghostEvents < 4 && System.currentTimeMillis() < eventDeadline) {
            drain();
            ghostEvents = upserts.stream().filter(JobUpsertedEvent::ghostJobSuspected)
                    .map(JobUpsertedEvent::jobId).distinct().count();
        }
        assertThat(ghostEvents).as("one ghost-flag event per flagged job").isEqualTo(4);
    }

    @Test
    void adminEndpointsRequireAdminRole() throws Exception {
        String user = TestJwt.tokenFor(UUID.randomUUID());
        String admin = TestJwt.tokenWithRoles(UUID.randomUUID(), "ADMIN");

        assertThat(get("/api/ingestion/runs", user).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/ingestion/runs", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var sources = get("/api/ingestion/sources", admin);
        assertThat(sources.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode list = mapper.readTree(sources.getBody());
        assertThat(list.isArray()).isTrue();
        assertThat(list.toString()).contains("greenhouse:acmepay");
    }

    // ---------- helpers ----------

    private org.springframework.http.ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = IngestionFlowIT.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes());
        }
    }

    private String sourceStatus(String key) {
        return jdbc.queryForObject("select status from sources where source_key = ?", String.class, key);
    }

    private boolean isOpen(UUID jobId) {
        return jdbc.queryForObject("select closed_at is null from ingested_postings where job_id = ?", Boolean.class, jobId);
    }

    private static JobUpsertedEvent byTitle(Map<UUID, JobUpsertedEvent> events, String title) {
        return events.values().stream().filter(e -> e.title().equals(title)).findFirst().orElseThrow();
    }

    private synchronized List<Object> drain() throws Exception {
        for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(300))) {
            if (r.topic().equals(Topics.JOB_UPSERTED)) {
                JobUpsertedEvent e = mapper.readValue(r.value(), JobUpsertedEvent.class);
                assertThat(r.key()).isEqualTo(e.jobId().toString());
                upserts.add(e);
            } else {
                closes.add(mapper.readValue(r.value(), JobClosedEvent.class));
            }
        }
        List<Object> all = new ArrayList<>(upserts);
        all.addAll(closes);
        return all;
    }

    private Map<UUID, JobUpsertedEvent> awaitUpserts(int distinct) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            drain();
            Map<UUID, JobUpsertedEvent> latest = upserts.stream()
                    .collect(Collectors.toMap(JobUpsertedEvent::jobId, e -> e, (a, b) -> b));
            if (latest.size() >= distinct) {
                Thread.sleep(800); // let any follow-up (e.g. ATS overriding Adzuna) arrive
                drain();
                return upserts.stream().collect(Collectors.toMap(JobUpsertedEvent::jobId, e -> e, (a, b) -> b));
            }
        }
        throw new AssertionError("Expected " + distinct + " jobs, got " + upserts.size() + " events");
    }

    private JobUpsertedEvent awaitUpsert(java.util.function.Predicate<JobUpsertedEvent> match) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            drain();
            for (JobUpsertedEvent e : upserts) {
                if (match.test(e)) {
                    return e;
                }
            }
        }
        throw new AssertionError("Expected JobUpserted not received");
    }

    private List<JobClosedEvent> awaitCloses(int n) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            drain();
            if (closes.size() >= n) {
                return List.copyOf(closes);
            }
        }
        throw new AssertionError("Expected " + n + " JobClosed events, got " + closes.size());
    }
}
