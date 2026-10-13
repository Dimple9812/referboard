package com.referboard.referral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.events.Topics;
import com.referboard.referral.service.ReminderService;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * referral-service against real PostgreSQL and embedded Kafka. job-service, profile-service and the Anthropic API
 * are a MockWebServer, so the Feign clients, token forwarding, the real LLM client and the draft guard all run.
 * A movable clock drives the reminders.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.referral.reminder-scan-interval-ms=3600000",
        "referboard.ai.llm.provider=anthropic",
        "referboard.ai.llm.anthropic.api-key=test-key",
        "referboard.ai.llm.anthropic.model=claude-test",
        "referboard.ai.llm.max-attempts=1"
})
@EmbeddedKafka(partitions = 1, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.REFERRAL_STATUS_CHANGED, Topics.REFERRAL_REMINDER_DUE})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReferralFlowIT {

    static final UUID USER = UUID.randomUUID();
    static final UUID OTHER_USER = UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final UUID JOB = UUID.randomUUID();
    static final UUID MISSING_JOB = UUID.randomUUID();
    static final UUID BROKEN_JOB = UUID.randomUUID();
    static final MovableClock CLOCK = new MovableClock(Instant.parse("2026-10-01T09:00:00Z"));

    static MockWebServer upstream;
    static final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    static volatile String llmReply;
    static Consumer<String, String> consumer;
    static final List<ConsumerRecord<String, String>> received = new ArrayList<>();
    static String contactId;

    @TestConfiguration
    static class Overrides {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) throws IOException {
        TestPostgres.register(registry, "referral");
        upstream = new MockWebServer();
        upstream.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requests.add(request);
                String path = request.getPath();
                if (path.equals("/api/jobs/" + JOB)) {
                    return json(JOB_JSON);
                }
                if (path.equals("/api/jobs/" + BROKEN_JOB)) {
                    return new MockResponse().setResponseCode(503);
                }
                if (path.startsWith("/api/jobs/")) {
                    return new MockResponse().setResponseCode(404).setBody("{\"code\":\"NOT_FOUND\"}");
                }
                if (path.equals("/api/profile")) {
                    return json(PROFILE_JSON);
                }
                if (path.equals("/v1/messages")) {
                    return json(llmReply);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        upstream.start();
        String base = "http://localhost:" + upstream.getPort();
        registry.add("referboard.clients.job-service-url", () -> base);
        registry.add("referboard.clients.profile-service-url", () -> base);
        registry.add("referboard.ai.llm.anthropic.base-url", () -> base);
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ReminderService reminderService;

    @BeforeAll
    static void subscribe(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("referral-it-" + UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromEmbeddedTopics(consumer, Topics.REFERRAL_STATUS_CHANGED, Topics.REFERRAL_REMINDER_DUE);
    }

    @AfterAll
    static void close() throws IOException {
        consumer.close();
        upstream.shutdown();
    }

    @Test
    @Order(1)
    void suggestsPeopleWithSearchLinksAndForwardsTheUsersToken() throws Exception {
        JsonNode s = json(call(HttpMethod.GET, "/api/referrals/jobs/" + JOB + "/suggestions", null, TOKEN), 200);
        assertThat(s.get("companyName").asText()).isEqualTo("Northwind");
        List<String> types = new ArrayList<>();
        s.get("people").forEach(p -> types.add(p.get("personType").asText()));
        assertThat(types).containsExactly("RECRUITER", "ENGINEERING_MANAGER", "TEAM_MEMBER", "ALUMNI", "EX_COLLEAGUE");
        assertThat(s.at("/people/3/linkedinSearchUrl").asText())
                .isEqualTo("https://www.linkedin.com/search/results/people/?keywords=Northwind%20IIT%20Madras"
                        + "&origin=GLOBAL_SEARCH_HEADER");

        RecordedRequest jobCall = requests.stream().filter(r -> r.getPath().startsWith("/api/jobs/")).findFirst()
                .orElseThrow();
        assertThat(jobCall.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(jobCall.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(requests).noneMatch(r -> String.valueOf(r.getRequestUrl()).contains("linkedin"));

        assertThat(call(HttpMethod.GET, "/api/referrals/jobs/" + MISSING_JOB + "/suggestions", null, TOKEN)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> down = call(HttpMethod.GET, "/api/referrals/jobs/" + BROKEN_JOB + "/suggestions",
                null, TOKEN);
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(down.getBody()).contains("UPSTREAM_UNAVAILABLE");
        assertThat(call(HttpMethod.GET, "/api/referrals/jobs/" + JOB + "/suggestions", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(2)
    void addsContactsWithValidation() throws Exception {
        String base = "/api/referrals/jobs/" + JOB + "/contacts";
        ResponseEntity<String> badUrl = call(HttpMethod.POST, base, Map.of("fullName", "X",
                "profileUrl", "https://www.linkedin.com/company/northwind", "personType", "RECRUITER"), TOKEN);
        assertThat(badUrl.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badUrl.getBody()).contains("INVALID_PROFILE_URL");
        assertThat(call(HttpMethod.POST, base, Map.of("fullName", "", "personType", "RECRUITER"), TOKEN)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode c = json(call(HttpMethod.POST, base, Map.of("fullName", "Priya Sharma",
                "profileUrl", "in.linkedin.com/in/priya-sharma/?trk=x", "headline", "SDE II at Northwind | ex-Flipkart",
                "personType", "ALUMNI"), TOKEN), 201);
        contactId = c.get("id").asText();
        assertThat(c.get("status").asText()).isEqualTo("NOT_CONTACTED");
        assertThat(c.get("profileUrl").asText()).isEqualTo("https://www.linkedin.com/in/priya-sharma");
        assertThat(c.get("jobTitle").asText()).isEqualTo("Senior Backend Engineer (Java)");

        ResponseEntity<String> dup = call(HttpMethod.POST, base, Map.of("fullName", "Priya S",
                "profileUrl", "https://www.linkedin.com/in/priya-sharma", "personType", "ALUMNI"), TOKEN);
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(json(call(HttpMethod.GET, base, null, TOKEN), 200)).hasSize(1);
        assertThat(call(HttpMethod.GET, "/api/referrals/contacts/" + contactId, null, TestJwt.tokenFor(OTHER_USER))
                .getStatusCode()).as("other users can't see it").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(3)
    void draftsWithTheLlmAndReplacesAnythingInvented() throws Exception {
        llmReply = anthropicToolUse(Map.of(
                "warmConnectionNote", "Hi Priya, fellow IIT Madras alum here! I'm a backend engineer keen on the "
                        + "Senior Backend Engineer role at Northwind and would love to connect.",
                "conciseConnectionNote", "Hi Priya, IIT Madras alum and backend engineer interested in Northwind's "
                        + "backend role. Would love to connect.",
                "warmReferralRequest", "Hi Priya, thanks for connecting! I mentored 6 interns at Lumen Pay... "
                        + "would you be open to referring me?",
                "conciseReferralRequest", "Hi Priya, I'm applying for the Senior Backend Engineer role at Northwind. "
                        + "I cut p99 latency of our payments API by 40%. Would you be open to referring me? Asha"));

        JsonNode set = json(call(HttpMethod.POST, "/api/referrals/contacts/" + contactId + "/drafts",
                Map.of("type", "INTRO"), TOKEN), 201);
        assertThat(set.get("generatedBy").asText()).isEqualTo("AI");
        JsonNode d = set.get("drafts");
        assertThat(d).hasSize(4);
        assertThat(d.get(0).get("model").asText()).isEqualTo("anthropic:claude-test");
        assertThat(d.get(0).get("charCount").asInt()).isLessThanOrEqualTo(300);
        assertThat(d.get(2).get("kind").asText()).isEqualTo("REFERRAL_REQUEST");
        assertThat(d.get(2).get("model").asText()).as("invented '6 interns' replaced").isEqualTo("template");
        assertThat(d.get(2).get("content").asText()).doesNotContain("interns").contains("fellow IIT Madras alum");
        assertThat(d.get(3).get("model").asText()).isEqualTo("anthropic:claude-test");
        assertThat(set.get("notices").toString()).contains("1 AI draft(s)");

        RecordedRequest llmCall = requests.stream().filter(r -> r.getPath().equals("/v1/messages")).findFirst()
                .orElseThrow();
        String body = llmCall.getBody().readUtf8();
        assertThat(body).contains("<FACTS>").contains("Cut p99 latency").contains("\"tool_choice\"");
        assertThat(body).as("job skills the user lacks are not offered as matching")
                .doesNotContain("\"skillsMatchingTheJob\" : [ \"Java\", \"Spring Boot\", \"Apache Kafka\", \"Kubernetes\"");

        String draftId = d.get(1).get("id").asText();
        JsonNode copied = json(call(HttpMethod.POST, "/api/referrals/drafts/" + draftId + "/copied", null, TOKEN), 200);
        assertThat(copied.get("copiedAt").isNull()).isFalse();
        assertThat(json(call(HttpMethod.GET, "/api/referrals/contacts/" + contactId + "/drafts", null, TOKEN), 200))
                .hasSize(4);
    }

    @Test
    @Order(4)
    void pipelineTransitionsPublishEventsAndScheduleReminders() throws Exception {
        ResponseEntity<String> bad = status("NO_RESPONSE");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bad.getBody()).contains("INVALID_TRANSITION");

        assertThat(json(status("REQUEST_SENT"), 200).get("requestSentAt").isNull()).isFalse();
        JsonNode connected = json(status("CONNECTED"), 200);
        assertThat(connected.get("connectedAt").asText()).isEqualTo("2026-10-01T09:00:00Z");

        JsonNode detail = json(call(HttpMethod.GET, "/api/referrals/contacts/" + contactId, null, TOKEN), 200);
        assertThat(detail.get("history")).hasSize(3);
        assertThat(detail.at("/reminders/0/type").asText()).isEqualTo("SEND_REFERRAL_MESSAGE");
        assertThat(detail.at("/reminders/0/status").asText()).isEqualTo("PENDING");
        assertThat(detail.at("/reminders/0/dueAt").asText()).isEqualTo("2026-10-03T09:00:00Z");

        List<JsonNode> changes = awaitEvents(Topics.REFERRAL_STATUS_CHANGED, 2);
        assertThat(changes.get(1).get("previousStatus").asText()).isEqualTo("REQUEST_SENT");
        assertThat(changes.get(1).get("newStatus").asText()).isEqualTo("CONNECTED");
        assertThat(changes.get(1).get("jobId").asText()).isEqualTo(JOB.toString());
        assertThat(changes.get(1).get("jobTitle").asText()).isEqualTo("Senior Backend Engineer (Java)");
        assertThat(changes.get(1).get("companyName").asText()).isEqualTo("Northwind");
        assertThat(changes.get(1).at("/metadata/correlationId").asText()).isNotBlank();

        // Not due yet
        CLOCK.advance(Duration.ofDays(1));
        assertThat(reminderService.scanOnce()).isZero();

        // Due: fires with a drafted referral message
        CLOCK.advance(Duration.ofDays(1).plusMinutes(1));
        assertThat(reminderService.scanOnce()).isEqualTo(1);
        JsonNode active = json(call(HttpMethod.GET, "/api/referrals/reminders", null, TOKEN), 200);
        assertThat(active).hasSize(1);
        assertThat(active.at("/0/message").asText()).isEqualTo("Connected with Priya Sharma 2 days ago — send your "
                + "referral message for Senior Backend Engineer (Java) at Northwind.");
        assertThat(active.at("/0/draftedFollowUp").asText()).startsWith("Hi Priya,").contains("referring me");

        JsonNode due = awaitEvents(Topics.REFERRAL_REMINDER_DUE, 1).getFirst();
        assertThat(due.get("reminderType").asText()).isEqualTo("SEND_REFERRAL_MESSAGE");
        assertThat(due.get("companyName").asText()).isEqualTo("Northwind");
        assertThat(due.get("draftedFollowUp").asText()).isEqualTo(active.at("/0/draftedFollowUp").asText());

        // Asking for the referral completes that reminder and schedules a 5-day follow-up
        json(status("REFERRAL_ASKED"), 200);
        assertThat(json(call(HttpMethod.GET, "/api/referrals/reminders", null, TOKEN), 200)).isEmpty();
        CLOCK.advance(Duration.ofDays(5).plusMinutes(1));
        assertThat(reminderService.scanOnce()).isEqualTo(1);
        JsonNode followUp = json(call(HttpMethod.GET, "/api/referrals/reminders", null, TOKEN), 200);
        assertThat(followUp.at("/0/type").asText()).isEqualTo("FOLLOW_UP");
        assertThat(followUp.at("/0/message").asText()).startsWith("No reply from Priya Sharma in 5 days");
        String reminderId = followUp.at("/0/id").asText();
        assertThat(json(call(HttpMethod.PATCH, "/api/referrals/reminders/" + reminderId, Map.of("status", "DONE"),
                TOKEN), 200).get("status").asText()).isEqualTo("DONE");

        // A reminder whose contact moved on before it was due never fires
        json(status("NO_RESPONSE"), 200);
        json(status("REFERRAL_ASKED"), 200);
        json(status("REFERRED"), 200);
        CLOCK.advance(Duration.ofDays(6));
        assertThat(reminderService.scanOnce()).isZero();   // REFERRED obsoleted the pending follow-up up front
        assertThat(awaitEvents(Topics.REFERRAL_REMINDER_DUE, 2)).hasSize(2);
        assertThat(status("CONNECTED").getStatusCode()).as("REFERRED is terminal").isEqualTo(HttpStatus.CONFLICT);

        JsonNode pipeline = json(call(HttpMethod.GET, "/api/referrals/pipeline", null, TOKEN), 200);
        assertThat(pipeline.at("/byStatus/REFERRED").asLong()).isEqualTo(1);
        assertThat(pipeline.get("total").asLong()).isEqualTo(1);
        assertThat(pipeline.get("activeReminders").asLong()).isZero();

        JsonNode crm = json(call(HttpMethod.GET, "/api/referrals/contacts?status=REFERRED&status=CONNECTED", null,
                TOKEN), 200);
        assertThat(crm.get("total").asLong()).isEqualTo(1);
        assertThat(crm.at("/items/0/companyName").asText()).isEqualTo("Northwind");
        assertThat(crm.at("/items/0/referredAt").asText()).isNotBlank();
    }

    @Test
    @Order(5)
    void deletingAContactHidesIt() throws Exception {
        assertThat(call(HttpMethod.DELETE, "/api/referrals/contacts/" + contactId, null, TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(json(call(HttpMethod.GET, "/api/referrals/jobs/" + JOB + "/contacts", null, TOKEN), 200)).isEmpty();
        assertThat(call(HttpMethod.GET, "/api/referrals/contacts/" + contactId, null, TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------------------------------------------

    private ResponseEntity<String> status(String status) {
        return call(HttpMethod.POST, "/api/referrals/contacts/" + contactId + "/status", Map.of("status", status), TOKEN);
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
        return mapper.readTree(r.getBody());
    }

    private List<JsonNode> awaitEvents(String topic, int atLeast) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            KafkaTestUtils.getRecords(consumer, Duration.ofMillis(300)).forEach(received::add);
            List<JsonNode> out = new ArrayList<>();
            for (ConsumerRecord<String, String> r : received) {
                if (r.topic().equals(topic)) {
                    out.add(mapper.readTree(r.value()));
                }
            }
            if (out.size() >= atLeast) {
                return out;
            }
        }
        throw new AssertionError("Expected " + atLeast + " events on " + topic);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String anthropicToolUse(Map<String, String> input) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "id", "msg_test", "type", "message", "role", "assistant", "model", "claude-test",
                "stop_reason", "tool_use",
                "content", List.of(Map.of("type", "tool_use", "id", "toolu_1", "name", "referral_drafts",
                        "input", input)),
                "usage", Map.of("input_tokens", 10, "output_tokens", 10)));
    }

    static final String JOB_JSON = """
            {"job": {"id": "%s", "title": "Senior Backend Engineer (Java)", "companyName": "Northwind",
                     "location": "Bengaluru, India", "applyUrl": "https://boards.greenhouse.io/northwind/jobs/4012345",
                     "skills": [{"name": "java", "label": "Java"}, {"name": "spring boot", "label": "Spring Boot"},
                                {"name": "kafka", "label": "Apache Kafka"}, {"name": "kubernetes", "label": "Kubernetes"}]},
             "descriptionHtml": "<p>Build <b>settlement</b> services.</p>", "status": "OPEN", "match": null}
            """.formatted(JOB);

    static final String PROFILE_JSON = """
            {"userId": "x", "exists": true, "fullName": "Asha Rao", "headline": "Backend engineer",
             "currentTitle": "Backend Engineer", "currentCompany": "Lumen Pay", "totalExperienceYears": 4.5,
             "college": "IIT Madras",
             "skills": [{"name": "java", "displayName": "Java"}, {"name": "spring boot", "displayName": "Spring Boot"},
                        {"name": "kafka", "displayName": "Apache Kafka"}, {"name": "postgresql", "displayName": "PostgreSQL"}],
             "experiences": [
               {"company": "Lumen Pay", "title": "Backend Engineer",
                "bullets": ["Cut p99 latency of our payments API by 40% with Redis caching", "Own the ledger service"]},
               {"company": "Flipkart", "title": "SDE", "bullets": ["Built order tracking"]}]}
            """;

    /** A clock tests can move forward. */
    static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant start) {
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
}
