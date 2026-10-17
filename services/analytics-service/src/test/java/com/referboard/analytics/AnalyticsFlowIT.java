package com.referboard.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.referboard.events.AnalysisCompletedEvent;
import com.referboard.events.ApplicationStatusChangedEvent;
import com.referboard.events.DomainEvent;
import com.referboard.events.EventMetadata;
import com.referboard.events.InterviewPrepCreatedEvent;
import com.referboard.events.ProStatusChangedEvent;
import com.referboard.events.ReferralStatusChangedEvent;
import com.referboard.events.ResumeVersionCreatedEvent;
import com.referboard.events.Topics;
import com.referboard.events.UserRegisteredEvent;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** A job search replayed as events (with duplicates and out-of-order delivery), then read back as insights. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@EmbeddedKafka(partitions = 1, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers", topics = {
        Topics.USER_REGISTERED, Topics.APPLICATION_STATUS_CHANGED, Topics.REFERRAL_STATUS_CHANGED, Topics.ANALYSIS_COMPLETED,
        Topics.RESUME_VERSION_CREATED, Topics.INTERVIEW_PREP_CREATED, Topics.PRO_STATUS_CHANGED})
class AnalyticsFlowIT {

    static final UUID USER = UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TestConfiguration
    static class Jwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, "analytics");
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aJobSearchBecomesInsightsAndAdminMetrics() throws Exception {
        Instant t0 = Instant.now().minus(Duration.ofDays(20));
        List<DomainEvent> events = new ArrayList<>();
        events.add(new UserRegisteredEvent(meta(t0), USER, "asha@example.com", "Asha Rao", "LOCAL"));
        // 6 referred applications, 4 of which reach an interview; 6 cold ones, 1 interview.
        for (int i = 0; i < 12; i++) {
            UUID app = UUID.randomUUID();
            Instant t = t0.plus(Duration.ofDays(1 + i));
            boolean referred = i < 6;
            events.add(stage(app, null, "SAVED", t));
            events.add(stage(app, "SAVED", "APPLIED", t.plus(Duration.ofHours(2))));
            if (referred) {
                events.add(stage(app, "APPLIED", "REFERRED", t.plus(Duration.ofDays(1))));
            }
            if ((referred && i < 4) || i == 6) {
                events.add(stage(app, referred ? "REFERRED" : "APPLIED", "INTERVIEW", t.plus(Duration.ofDays(4))));
            }
            if (i == 11) {
                events.add(stage(app, "APPLIED", "REJECTED", t.plus(Duration.ofDays(3))));
            }
        }
        UUID late = UUID.randomUUID();
        // Out of order: the interview arrives before the card's creation; the card must still end at INTERVIEW.
        events.add(stage(late, "APPLIED", "INTERVIEW", t0.plus(Duration.ofDays(15))));
        events.add(stage(late, null, "SAVED", t0.plus(Duration.ofDays(10))));
        // Referral contacts: 3 added, 3 connected, 2 asked, 1 referred.
        for (int i = 0; i < 3; i++) {
            UUID c = UUID.randomUUID();
            Instant t = t0.plus(Duration.ofDays(i));
            events.add(contact(c, null, "NOT_CONTACTED", t));
            events.add(contact(c, "NOT_CONTACTED", "REQUEST_SENT", t.plus(Duration.ofHours(1))));
            events.add(contact(c, "REQUEST_SENT", "CONNECTED", t.plus(Duration.ofDays(1))));
            if (i < 2) {
                events.add(contact(c, "CONNECTED", "REFERRAL_ASKED", t.plus(Duration.ofDays(2))));
            }
            if (i == 0) {
                events.add(contact(c, "REFERRAL_ASKED", "REFERRED", t.plus(Duration.ofDays(3))));
            }
        }
        events.add(new AnalysisCompletedEvent(meta(t0), USER, UUID.randomUUID(), null, "TEXT", 60, "PARTIAL", Instant.now()));
        events.add(new AnalysisCompletedEvent(meta(t0), USER, UUID.randomUUID(), null, "TEXT", 80, "GOOD", Instant.now()));
        events.add(new ResumeVersionCreatedEvent(meta(Instant.now()), USER, UUID.randomUUID(), "v", null, UUID.randomUUID(), 60, 72, "PDF"));
        events.add(new InterviewPrepCreatedEvent(meta(Instant.now()), USER, UUID.randomUUID(), null, 24, Instant.now()));
        events.add(new ProStatusChangedEvent(meta(Instant.now()), USER, "PURCHASE", "PRO_MONTH",
                Instant.now().plus(Duration.ofDays(30)), UUID.randomUUID(), 29900, "INR", Instant.now()));
        for (DomainEvent e : events) {
            send(e);
        }
        send(events.get(1));   // a redelivery changes nothing

        int expected = events.size();
        await(() -> jdbc.queryForObject("select count(*) from processed_events", Integer.class) >= expected, "projections");

        JsonNode in = get("/api/insights", TOKEN);
        assertThat(in.at("/funnel/saved").asInt()).isEqualTo(13);
        assertThat(in.at("/funnel/applied").asInt()).isEqualTo(13);
        assertThat(in.at("/funnel/referred").asInt()).isEqualTo(6);
        assertThat(in.at("/funnel/interview").asInt()).isEqualTo(6);
        assertThat(in.at("/funnel/rejected").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select stage from applications where application_id = ?", String.class, late))
                .isEqualTo("INTERVIEW");
        assertThat(in.at("/referredVsCold/referred/applications").asInt()).isEqualTo(6);
        assertThat(in.at("/referredVsCold/referred/interviews").asInt()).isEqualTo(4);
        assertThat(in.at("/referredVsCold/cold/applications").asInt()).isEqualTo(7);
        assertThat(in.at("/referredVsCold/cold/interviews").asInt()).isEqualTo(2);
        assertThat(in.at("/referrals/connected").asInt()).isEqualTo(3);
        assertThat(in.at("/referrals/asked").asInt()).isEqualTo(2);
        assertThat(in.at("/referrals/referred").asInt()).isEqualTo(1);
        assertThat(in.at("/resumes/averageScore").asInt()).isEqualTo(70);
        assertThat(in.at("/resumes/avgGain").asDouble()).isEqualTo(12.0);
        assertThat(in.at("/resumes/prepPacks").asInt()).isEqualTo(1);
        assertThat(in.get("weekly")).hasSize(12);
        assertThat(in.get("medianDaysToInterview").asDouble()).isBetween(2.0, 6.0);
        assertThat(in.get("highlights").toString())
                .contains("Referred applications reached an interview 4 of 6 times (67%), cold ones 2 of 7 (29%).")
                .contains("1 of the 2 people you asked for a referral have referred you.")
                .contains("Tailored resumes raised your match score by 12.0 points on average.");

        // Another user sees nothing of this; a new user gets an empty, honest page.
        JsonNode empty = get("/api/insights", TestJwt.tokenFor(UUID.randomUUID()));
        assertThat(empty.at("/funnel/saved").asInt()).isZero();
        assertThat(empty.at("/referredVsCold/referred/rate").isNull()).isTrue();
        assertThat(empty.get("highlights")).isEmpty();

        // Admin metrics need the ADMIN role.
        assertThat(call("/api/insights/admin", TOKEN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode admin = MAPPER.readTree(call("/api/insights/admin", TestJwt.tokenWithRoles(UUID.randomUUID(), "USER", "ADMIN")).getBody());
        assertThat(admin.get("users").asInt()).isEqualTo(1);
        assertThat(admin.get("proNow").asInt()).isEqualTo(1);
        assertThat(admin.get("paidConversionPct").asDouble()).isEqualTo(100.0);
        assertThat(admin.get("revenuePaise").asLong()).isEqualTo(29900);
        assertThat(call("/api/insights", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static ApplicationStatusChangedEvent stage(UUID app, String from, String to, Instant at) {
        return new ApplicationStatusChangedEvent(meta(at), USER, app, null, from, to, null, at);
    }

    private static ReferralStatusChangedEvent contact(UUID c, String from, String to, Instant at) {
        return new ReferralStatusChangedEvent(meta(at), USER, UUID.randomUUID(), "Engineer", "Northwind", null, null, c,
                "Priya", from, to, at);
    }

    private static EventMetadata meta(Instant at) {
        return new EventMetadata(UUID.randomUUID(), "Test", 1, at, "test", null);
    }

    private void send(DomainEvent e) throws Exception {
        kafka.send(e.topic(), e.key(), MAPPER.writeValueAsString(e)).get();
    }

    private JsonNode get(String path, String token) throws Exception {
        ResponseEntity<String> r = call(path, token);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getBody()).isTrue();
        return MAPPER.readTree(r.getBody());
    }

    private ResponseEntity<String> call(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private static void await(Supplier<Boolean> condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }
}
