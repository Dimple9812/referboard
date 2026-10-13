package com.referboard.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.events.DailyMatchDigestEvent;
import com.referboard.events.EventMetadata;
import com.referboard.events.JobClosedEvent;
import com.referboard.events.JobUpsertedEvent;
import com.referboard.events.ProfileUpdatedEvent;
import com.referboard.events.Topics;
import com.referboard.job.service.DigestService;
import com.referboard.job.service.MatchingService;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestPostgres;
import com.referboard.testsupport.TestRedis;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives job-service only through Kafka events and its HTTP API, against real PostgreSQL (FTS, pg_trgm, pgvector
 * HNSW), real Redis and an embedded Kafka broker. Embeddings are hand-made unit vectors with known cosines so the
 * expected match order is deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.jobs.match.refresh-interval-ms=3600000",
        "referboard.jobs.digest.cron=-"
})
@EmbeddedKafka(partitions = 1, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.JOB_UPSERTED, Topics.JOB_CLOSED, Topics.PROFILE_UPDATED, Topics.DAILY_MATCH_DIGEST,
                Topics.JOB_UPSERTED + ".DLT"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JobFlowIT {

    @TestConfiguration
    static class Jwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, "job");
        TestRedis.register(registry);
    }

    static final UUID USER = UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final Instant NOW = Instant.now();
    static final String MODEL = "test-model:1536";

    // Unit vectors on orthogonal axes: cos(user, backend) = 0.8, cos(user, ios) = 0.
    static final float[] USER_VEC = vec(Map.of(0, 0.8f, 1, 0.6f));
    static final float[] BACKEND_VEC = vec(Map.of(0, 1f));
    static final float[] IOS_VEC = vec(Map.of(2, 1f));
    static final float[] DATA_VEC = vec(Map.of(0, 0.5f, 2, 0.866f));

    static final UUID BACKEND = id("northwind|backend engineer java|bengaluru");
    static final UUID IOS = id("lumen|ios engineer|mumbai");
    static final UUID DATA = id("kestrel|data engineer|remote|in");
    static final UUID FRONTEND = id("quill|frontend engineer|pune");
    static final UUID GHOST = id("banyan|backend developer|bengaluru");

    private static Consumer<String, String> consumer;
    private static final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @Autowired
    TestRestTemplate rest;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MatchingService matching;
    @Autowired
    DigestService digest;

    @BeforeAll
    static void subscribe(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("job-it-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromEmbeddedTopics(consumer, Topics.DAILY_MATCH_DIGEST, Topics.JOB_UPSERTED + ".DLT");
    }

    @AfterAll
    static void close() {
        consumer.close();
    }

    @Test
    @Order(1)
    void ingestsJobsAndSearchesWithFilters() throws Exception {
        send(job(BACKEND, "Northwind Payments", "Backend Engineer (Java)", "Bengaluru, Karnataka", false, "SENIOR",
                List.of("java", "spring boot", "kafka", "postgresql"), "Build settlement services on Kafka.",
                2, BACKEND_VEC, 4_000_000L, false, "h1"));
        send(job(IOS, "Lumen Health", "iOS Engineer", "Mumbai", false, "MID", List.of("swift", "swiftui", "ios"),
                "Ship our patient app.", 10, IOS_VEC, 3_000_000L, false, "h2"));
        send(job(DATA, "Kestrel Logistics", "Data Engineer", "Remote - India", true, "MID",
                List.of("python", "spark", "sql"), "Pipelines with Spark.", 40, DATA_VEC, null, false, "h3"));
        send(job(FRONTEND, "Quill Labs", "Frontend Engineer", "Pune", false, "MID", List.of("react", "typescript"),
                "React and TypeScript.", 1, null, null, false, "h4"));
        send(job(GHOST, "Banyan Learning", "Backend Developer", "Bangalore", false, "MID", List.of("java", "mongodb"),
                "Java services.", 70, BACKEND_VEC, null, true, "h5"));
        await(() -> count("select count(*) from jobs") == 5, "5 jobs stored");

        JsonNode all = search("sort=RECENT");
        assertThat(all.get("total").asLong()).isEqualTo(5);
        assertThat(ids(all)).containsExactly(FRONTEND, BACKEND, IOS, DATA, GHOST);
        assertThat(all.get("items").get(0).get("matchPercent").isNull()).as("no profile yet").isTrue();
        assertThat(all.at("/items/1/skills/2/label").asText()).isEqualTo("Apache Kafka");

        assertThat(ids(search("q=kafka"))).containsExactly(BACKEND);
        assertThat(ids(search("q=\"settlement services\""))).containsExactly(BACKEND);
        assertThat(ids(search("location=Bangalore&sort=RECENT"))).as("Bangalore finds Bengaluru too")
                .containsExactly(BACKEND, GHOST);
        assertThat(ids(search("remote=true"))).containsExactly(DATA);
        assertThat(ids(search("postedWithinDays=7&sort=RECENT"))).containsExactly(FRONTEND, BACKEND);
        assertThat(ids(search("seniority=senior"))).containsExactly(BACKEND);
        assertThat(ids(search("skills=Java&sort=RECENT"))).containsExactly(BACKEND, GHOST);
        assertThat(ids(search("skills=Java&hideGhostJobs=true"))).containsExactly(BACKEND);
        assertThat(ids(search("company=lumen"))).containsExactly(IOS);
        assertThat(ids(search("role=backend engineer"))).containsExactly(BACKEND);
        assertThat(ids(search("role=bakend engineer"))).as("typo tolerated").containsExactly(BACKEND);
        assertThat(ids(search("role=ios engineer"))).as("not fooled by the shared word").containsExactly(IOS);

        JsonNode page0 = search("sort=RECENT&size=2&page=0");
        assertThat(page0.get("hasNext").asBoolean()).isTrue();
        assertThat(ids(search("sort=RECENT&size=2&page=2"))).containsExactly(GHOST);
        assertThat(search("sort=RECENT&size=2&page=2").get("hasNext").asBoolean()).isFalse();

        assertThat(redis.keys("jobs:search:v1:*")).as("search pages cached in Redis").isNotEmpty();

        ResponseEntity<String> bad = get("/api/jobs?size=500", TOKEN);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("VALIDATION_FAILED");
        assertThat(get("/api/jobs", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(2)
    void profileDrivesMatchScoresAndRecommendations() throws Exception {
        send(Topics.PROFILE_UPDATED, USER.toString(), profile(2, List.of("Backend Engineer"), List.of("Bangalore")));
        await(() -> count("select count(*) from job_matches where user_id = '" + USER + "'") > 0, "matches computed");

        JsonNode results = search("sort=RECENT");
        JsonNode backend = item(results, BACKEND);
        JsonNode ios = item(results, IOS);
        assertThat(backend.get("matchPercent").asInt()).isGreaterThan(75);
        assertThat(ios.get("matchPercent").asInt()).isLessThan(25);
        assertThat(backend.get("matchedSkills").toString()).contains("\"label\":\"Apache Kafka\"");

        JsonNode detail = json(get("/api/jobs/" + BACKEND, TOKEN));
        JsonNode match = detail.get("match");
        assertThat(match.get("available").asBoolean()).isTrue();
        assertThat(match.get("matchPercent").asInt()).isEqualTo(backend.get("matchPercent").asInt());
        assertThat(match.at("/components/skills").asDouble()).isEqualTo(1.0);
        assertThat(match.at("/components/semantic").asDouble()).as("cos 0.8 rescaled").isEqualTo(1.0);
        assertThat(match.get("missingSkills")).isEmpty();
        assertThat(match.get("reasons").toString()).contains("In a location you prefer");
        assertThat(detail.get("descriptionHtml").asText()).contains("settlement");

        JsonNode recommended = json(get("/api/jobs/recommended", TOKEN));
        assertThat(ids(recommended).getFirst()).isEqualTo(BACKEND);
        assertThat(ids(recommended)).doesNotContain(IOS);
        assertThat(ids(search("sort=MATCH")).getFirst()).isEqualTo(BACKEND);

        // a job ingested after the profile is scored by the background refresher
        UUID gurugram = id("orbit|backend engineer|gurugram");
        send(job(gurugram, "Orbit Fintech", "Backend Engineer", "Gurugram", false, "MID",
                List.of("java", "kafka", "docker"), "Payments platform.", 0, BACKEND_VEC, null, false, "h6"));
        await(() -> count("select count(*) from pending_match_jobs where job_id = '" + gurugram + "'") == 1, "queued");
        matching.refreshPending();
        assertThat(ids(json(get("/api/jobs/recommended", TOKEN)))).contains(gurugram);
        assertThat(count("select count(*) from pending_match_jobs")).isZero();

        // an older profile version arriving late is ignored
        send(Topics.PROFILE_UPDATED, USER.toString(), profile(1, List.of("iOS Engineer"), List.of("Mumbai")));
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("select profile_version from user_match_profiles where user_id = ?",
                Long.class, USER)).isEqualTo(2L);
    }

    @Test
    @Order(3)
    void updatesClosesIdempotencyAndDeadLetters() throws Exception {
        // flag-only update: null embedding + same content hash keeps the stored vector
        JobUpsertedEvent ghostUpdate = job(BACKEND, "Northwind Payments", "Backend Engineer (Java)",
                "Bengaluru, Karnataka", false, "SENIOR", List.of("java", "spring boot", "kafka", "postgresql"),
                "Build settlement services on Kafka.", 2, null, 4_000_000L, true, "h1");
        String payload = mapper.writeValueAsString(ghostUpdate);
        kafka.send(Topics.JOB_UPSERTED, BACKEND.toString(), payload).get();
        kafka.send(Topics.JOB_UPSERTED, BACKEND.toString(), payload).get(); // redelivery
        await(() -> jdbc.queryForObject("select ghost_job_suspected from jobs where id = ?", Boolean.class, BACKEND),
                "ghost flag applied");
        assertThat(jdbc.queryForObject("select embedding is not null from jobs where id = ?", Boolean.class, BACKEND))
                .isTrue();
        assertThat(count("select count(*) from processed_events where event_id = '"
                + ghostUpdate.metadata().eventId() + "'")).isEqualTo(1);

        // close
        send(Topics.JOB_CLOSED, IOS.toString(), mapper.writeValueAsString(new JobClosedEvent(
                meta("JobClosed"), IOS, NOW, JobClosedEvent.REASON_UNSEEN_7_DAYS)));
        await(() -> "CLOSED".equals(jdbc.queryForObject("select status from jobs where id = ?", String.class, IOS)),
                "job closed");
        redis.delete(redis.keys("jobs:search:v1:*"));
        assertThat(ids(search("sort=RECENT"))).doesNotContain(IOS);
        assertThat(json(get("/api/jobs/" + IOS, TOKEN)).get("status").asText()).isEqualTo("CLOSED");
        assertThat(get("/api/jobs/" + UUID.randomUUID(), TOKEN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // poison message goes to the DLT instead of blocking the partition
        kafka.send(Topics.JOB_UPSERTED, "bad", "{not json").get();
        ConsumerRecord<String, String> dead = awaitRecord(Topics.JOB_UPSERTED + ".DLT");
        assertThat(dead.value()).isEqualTo("{not json");
        // and the partition kept flowing
        UUID after = id("acme|sre|pune");
        send(job(after, "Acme", "SRE", "Pune", false, "MID", List.of("kubernetes"), "On-call.", 0, null, null,
                false, "h7"));
        await(() -> count("select count(*) from jobs where id = '" + after + "'") == 1, "consumer kept going");
    }

    @Test
    @Order(4)
    void dailyDigestPublishesTopNewMatchesOnce() throws Exception {
        assertThat(digest.run()).isEqualTo(1);
        ConsumerRecord<String, String> record = awaitRecord(Topics.DAILY_MATCH_DIGEST);
        DailyMatchDigestEvent event = mapper.readValue(record.value(), DailyMatchDigestEvent.class);
        assertThat(event.userId()).isEqualTo(USER);
        assertThat(event.matches()).isNotEmpty().allSatisfy(m -> assertThat(m.matchPercent()).isGreaterThanOrEqualTo(60));
        assertThat(event.matches().getFirst().jobId()).isEqualTo(BACKEND);
        assertThat(event.matches()).extracting(DailyMatchDigestEvent.MatchSummary::jobId).doesNotContain(IOS);

        assertThat(digest.run()).as("each match is digested once").isZero();
    }

    // ---------- fixtures ----------

    static float[] vec(Map<Integer, Float> components) {
        float[] v = new float[1536];
        components.forEach((i, x) -> v[i] = x);
        return v;
    }

    static UUID id(String dedupKey) {
        return UUID.nameUUIDFromBytes(dedupKey.getBytes(StandardCharsets.UTF_8));
    }

    static EventMetadata meta(String type) {
        return EventMetadata.of(type, "test", "it-" + UUID.randomUUID());
    }

    static JobUpsertedEvent job(UUID id, String company, String title, String location, boolean remote,
                                String seniority, List<String> skills, String description, int postedDaysAgo,
                                float[] embedding, Long salaryMax, boolean ghost, String hash) {
        Instant posted = NOW.minus(Duration.ofDays(postedDaysAgo));
        return new JobUpsertedEvent(meta("JobUpserted"), id, id.toString(), "greenhouse", "src-" + id, company, null,
                title, title.toLowerCase(), location, remote ? null : "IN", remote, "Full-time", seniority,
                "Engineering", "<p>" + description + "</p>", description, "https://example.com/" + id,
                salaryMax == null ? null : salaryMax / 2, salaryMax, salaryMax == null ? null : "INR",
                posted, posted, NOW, skills, embedding, embedding == null ? null : MODEL, ghost, hash);
    }

    String profile(long version, List<String> roles, List<String> locations) throws Exception {
        return mapper.writeValueAsString(new ProfileUpdatedEvent(meta("ProfileUpdated"), USER, version,
                "Backend engineer", List.of("java", "spring boot", "kafka", "postgresql", "docker"),
                new BigDecimal("4.5"), "IIT Roorkee", List.of("Razorpay"),
                new ProfileUpdatedEvent.Preferences(roles, locations, false, true, 2_500_000L, null),
                USER_VEC, MODEL, 1536));
    }

    void send(JobUpsertedEvent e) throws Exception {
        send(Topics.JOB_UPSERTED, e.jobId().toString(), mapper.writeValueAsString(e));
    }

    void send(String topic, String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get();
    }

    // ---------- helpers ----------

    JsonNode search(String query) throws Exception {
        return json(get("/api/jobs?" + query, TOKEN));
    }

    ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    JsonNode json(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getBody()).isTrue();
        return mapper.readTree(r.getBody());
    }

    static List<UUID> ids(JsonNode page) {
        List<UUID> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(UUID.fromString(i.get("id").asText())));
        return out;
    }

    static JsonNode item(JsonNode page, UUID id) {
        for (JsonNode i : page.get("items")) {
            if (i.get("id").asText().equals(id.toString())) {
                return i;
            }
        }
        throw new AssertionError("job " + id + " not in results");
    }

    long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    static ConsumerRecord<String, String> awaitRecord(String topic) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500))) {
                received.add(r);
            }
            for (ConsumerRecord<String, String> r : received) {
                if (r.topic().equals(topic)) {
                    received.remove(r);
                    return r;
                }
            }
        }
        throw new AssertionError("No record on " + topic);
    }
}
