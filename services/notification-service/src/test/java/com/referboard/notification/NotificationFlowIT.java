package com.referboard.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.referboard.events.DailyMatchDigestEvent;
import com.referboard.events.DomainEvent;
import com.referboard.events.EventMetadata;
import com.referboard.events.ReferralReminderDueEvent;
import com.referboard.events.Topics;
import com.referboard.events.UserRegisteredEvent;
import com.referboard.notification.email.EmailSender;
import com.referboard.testsupport.TestJwt;
import com.referboard.testsupport.TestPostgres;
import jakarta.mail.internet.MimeMessage;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * notification-service with real PostgreSQL, embedded Kafka and a real SMTP server (GreenMail): events in,
 * notifications and emails out, including an SMTP outage, opt-out by unsubscribe link and the daily email cap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.notification.sender-initial-delay-ms=3600000",
        "referboard.notification.sender-interval-ms=3600000",
        "referboard.notification.max-emails-per-user-per-day=3",
        "referboard.notification.app-base-url=https://app.referboard.test",
        "spring.mail.host=127.0.0.1",
        "spring.mail.properties.mail.smtp.connectiontimeout=1000"
})
@EmbeddedKafka(partitions = 1, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.USER_REGISTERED, Topics.REFERRAL_REMINDER_DUE, Topics.DAILY_MATCH_DIGEST,
                Topics.PRO_STATUS_CHANGED, Topics.APPLICATION_STATUS_CHANGED})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationFlowIT {

    static final UUID USER = UUID.randomUUID();
    static final UUID NO_EMAIL_USER = UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final int SMTP_PORT = freePort();
    static final GreenMail smtp = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", "smtp"));
    static String unsubscribeUrl;

    static {
        smtp.start();
    }

    @TestConfiguration
    static class Jwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry, "notification");
        registry.add("spring.mail.port", () -> SMTP_PORT);
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EmailSender sender;

    @AfterAll
    static void stop() {
        smtp.stop();
    }

    @Test
    @Order(1)
    void welcomesNewUsers() throws Exception {
        send(new UserRegisteredEvent(meta(), USER, "asha@example.com", "Asha Rao", "PASSWORD"));
        JsonNode page = await(() -> {
            JsonNode p = get("/api/notifications");
            return p.get("total").asInt() == 1 ? p : null;
        }, "welcome notification");
        assertThat(page.at("/items/0/type").asText()).isEqualTo("WELCOME");
        assertThat(page.get("unreadCount").asInt()).isEqualTo(1);
        assertThat(get("/api/notifications/preferences").toString())
                .isEqualTo("{\"emailReminders\":true,\"emailDigest\":true}");
        assertThat(count("select count(*) from email_deliveries")).as("welcome is in-app only").isZero();
    }

    @Test
    @Order(2)
    void reminderBecomesANotificationAndAnEscapedEmail() throws Exception {
        UUID reminderId = UUID.randomUUID();
        ReferralReminderDueEvent e = reminder(reminderId, "Rohan <b>Mehta</b>");
        send(e);
        send(new ReferralReminderDueEvent(meta(), reminderId, USER, e.contactId(), e.jobId(), e.contactName(),
                e.jobTitle(), e.companyName(), e.reminderType(), e.message(), e.draftedFollowUp(), e.dueAt()));
        await(() -> count("select count(*) from email_deliveries") == 1 ? true : null, "email queued");
        assertThat(sender.sendDue()).isEqualTo(1);

        MimeMessage[] mails = smtp.getReceivedMessages();
        assertThat(mails).hasSize(1);
        MimeMessage m = mails[0];
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("asha@example.com");
        assertThat(m.getSubject()).isEqualTo("Send your referral message to Rohan <b>Mehta</b>");
        assertThat(m.getHeader("List-Unsubscribe-Post")[0]).isEqualTo("List-Unsubscribe=One-Click");
        unsubscribeUrl = m.getHeader("List-Unsubscribe")[0].replaceAll("^<|>$", "");
        String body = html(m);
        assertThat(body).contains("Rohan &lt;b&gt;Mehta&lt;/b&gt;").doesNotContain("<b>Mehta")
                .contains("https://app.referboard.test/referrals/contacts/" + e.contactId());

        JsonNode page = get("/api/notifications");
        assertThat(page.get("total").asInt()).as("redelivered reminder is deduplicated").isEqualTo(2);
        assertThat(page.at("/items/0/type").asText()).isEqualTo("REFERRAL_REMINDER");
        assertThat(page.at("/items/0/link").asText()).isEqualTo("/referrals/contacts/" + e.contactId());
        assertThat(page.at("/items/0/data/draftedMessage").asText()).startsWith("Hi Rohan");
    }

    @Test
    @Order(3)
    void smtpOutageIsRetriedWithBackoff() throws Exception {
        smtp.stop();
        send(reminder(UUID.randomUUID(), "Kavya Nair"));
        await(() -> count("select count(*) from email_deliveries where status = 'QUEUED'") == 1 ? true : null,
                "email queued");
        sender.sendDue();
        Map<String, Object> row = jdbc.queryForMap(
                "select attempts, last_error, next_attempt_at > now() as later from email_deliveries where status = 'QUEUED'");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isNotNull();
        assertThat(row.get("later")).isEqualTo(true);
        assertThat(sender.sendDue()).as("not due yet").isZero();

        smtp.start();
        jdbc.update("update email_deliveries set next_attempt_at = now() where status = 'QUEUED'");
        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(smtp.getReceivedMessages()).hasSize(1);   // fresh server instance
        assertThat(smtp.getReceivedMessages()[0].getSubject()).isEqualTo("Send your referral message to Kavya Nair");
        assertThat(count("select count(*) from email_deliveries where status = 'SENT' and attempts = 2")).isEqualTo(1);
    }

    @Test
    @Order(4)
    void digestEmailThenUnsubscribeByLink() throws Exception {
        send(digest(LocalDate.of(2026, 10, 13)));
        await(() -> count("select count(*) from email_deliveries where template = 'daily-digest'") == 1 ? true : null,
                "digest queued");
        sender.sendDue();
        MimeMessage digestMail = smtp.getReceivedMessages()[1];
        assertThat(digestMail.getSubject()).isEqualTo("Your top 2 new job matches for Tue, 13 Oct");
        String digestUnsub = digestMail.getHeader("List-Unsubscribe")[0].replaceAll("^<|>$", "");
        assertThat(digestUnsub).contains("category=digest");

        // The link works without logging in
        URI uri = URI.create(digestUnsub);
        ResponseEntity<String> page = rest.exchange(uri.getPath() + "?" + uri.getRawQuery(), HttpMethod.GET, null,
                String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("receive the daily match digest any more");
        assertThat(rest.postForEntity("/api/notifications/unsubscribe?token=" + UUID.randomUUID(), null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/notifications/preferences").get("emailDigest").asBoolean()).isFalse();
        assertThat(get("/api/notifications/preferences").get("emailReminders").asBoolean()).isTrue();

        send(digest(LocalDate.of(2026, 10, 14)));
        await(() -> count("select count(*) from email_deliveries where skip_reason = 'OPTED_OUT'") == 1 ? true : null,
                "second digest not emailed");
        assertThat(get("/api/notifications").at("/items/0/title").asText())
                .as("still in the app").isEqualTo("2 new job matches for you");
    }

    @Test
    @Order(5)
    void dailyEmailCapAndUsersWithoutAnAddress() throws Exception {
        send(reminder(UUID.randomUUID(), "Fourth Person"));   // 3 already sent today (limit 3)
        await(() -> count("select count(*) from email_deliveries where skip_reason = 'DAILY_LIMIT'") == 1 ? true : null,
                "capped");

        send(new ReferralReminderDueEvent(meta(), UUID.randomUUID(), NO_EMAIL_USER, UUID.randomUUID(),
                UUID.randomUUID(), "X", "SDE", "Co", "FOLLOW_UP", "No reply from X in 5 days", "Hi X", Instant.now()));
        await(() -> count("select count(*) from notifications where user_id = '" + NO_EMAIL_USER + "'") == 1 ? true : null,
                "in-app notification for a user we can't email");
        assertThat(count("select count(*) from email_deliveries where user_id = '" + NO_EMAIL_USER + "'")).isZero();
    }

    @Test
    @Order(6)
    void inboxApi() throws Exception {
        long unread = get("/api/notifications/unread-count").get("count").asLong();
        assertThat(unread).isEqualTo(6);
        JsonNode first = get("/api/notifications?size=2");
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        String id = first.at("/items/0/id").asText();
        assertThat(json(call(HttpMethod.POST, "/api/notifications/" + id + "/read", null, TOKEN)).get("read")
                .asBoolean()).isTrue();
        assertThat(get("/api/notifications?unreadOnly=true").get("total").asInt()).isEqualTo(5);
        assertThat(json(call(HttpMethod.POST, "/api/notifications/read-all", null, TOKEN)).get("updated").asInt())
                .isEqualTo(5);
        assertThat(call(HttpMethod.DELETE, "/api/notifications/" + id, null, TOKEN).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/notifications").get("total").asInt()).isEqualTo(5);

        String other = TestJwt.tokenFor(UUID.randomUUID());
        assertThat(call(HttpMethod.POST, "/api/notifications/" + first.at("/items/1/id").asText() + "/read", null,
                other).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/notifications", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode prefs = json(call(HttpMethod.PUT, "/api/notifications/preferences", Map.of("emailDigest", true), TOKEN));
        assertThat(prefs.get("emailDigest").asBoolean()).isTrue();
        assertThat(prefs.get("emailReminders").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------

    private ReferralReminderDueEvent reminder(UUID reminderId, String contactName) {
        return new ReferralReminderDueEvent(meta(), reminderId, USER, UUID.randomUUID(), UUID.randomUUID(), contactName,
                "Backend Engineer", "Northwind", "SEND_REFERRAL_MESSAGE",
                "Connected with " + contactName + " 2 days ago — send your referral message.",
                "Hi " + contactName.split(" ")[0] + ",\n\nThanks for connecting!", Instant.now());
    }

    @Test
    @Order(7)
    void paymentsAndInterviewsBecomeInAppNotifications() throws Exception {
        UUID order = UUID.randomUUID();
        long before = count("select count(*) from email_deliveries");
        var pro = new com.referboard.events.ProStatusChangedEvent(meta(), USER, "PURCHASE", "PRO_MONTH",
                Instant.parse("2026-11-15T10:00:00Z"), order, 29900, "INR", Instant.now());
        send(pro);
        send(pro);   // redelivered: still one notification
        UUID app = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        send(new com.referboard.events.ApplicationStatusChangedEvent(meta(), USER, app, job, "APPLIED", "REFERRED", null, Instant.now()));
        send(new com.referboard.events.ApplicationStatusChangedEvent(meta(), USER, app, job, "REFERRED", "INTERVIEW", null, Instant.now()));
        send(new com.referboard.events.ApplicationStatusChangedEvent(meta(), USER, app, job, "OFFER", "INTERVIEW", null, Instant.now()));
        JsonNode page = await(() -> {
            JsonNode p = get("/api/notifications");
            long billing = 0;
            long interview = 0;
            for (JsonNode n : p.get("items")) {
                billing += n.get("type").asText().equals("BILLING") ? 1 : 0;
                interview += n.get("type").asText().equals("INTERVIEW") ? 1 : 0;
            }
            return billing == 1 && interview == 1 ? p : null;
        }, "billing and interview notifications");
        JsonNode billing = null;
        JsonNode interview = null;
        for (JsonNode n : page.get("items")) {
            if (n.get("type").asText().equals("BILLING")) billing = n;
            if (n.get("type").asText().equals("INTERVIEW")) interview = n;
        }
        assertThat(billing.get("title").asText()).isEqualTo("Pro is on");
        assertThat(billing.get("body").asText()).contains("15 November 2026");
        assertThat(interview.get("link").asText()).isEqualTo("/interview?jobId=" + job);
        Thread.sleep(500);
        assertThat(count("select count(*) from notifications where type = 'INTERVIEW'")).as("once per application").isEqualTo(1);
        assertThat(count("select count(*) from email_deliveries")).as("in-app only").isEqualTo(before);
    }

    private DailyMatchDigestEvent digest(LocalDate date) {
        return new DailyMatchDigestEvent(meta(), USER, date, List.of(
                new DailyMatchDigestEvent.MatchSummary(UUID.randomUUID(), "Backend Engineer", "Northwind", "Bengaluru",
                        false, 88, List.of("Java", "Kafka")),
                new DailyMatchDigestEvent.MatchSummary(UUID.randomUUID(), "Platform Engineer", "Kestrel", "Remote",
                        true, 71, List.of("Kubernetes"))));
    }

    private static EventMetadata meta() {
        return new EventMetadata(UUID.randomUUID(), "Test", 1, Instant.now(), "test", "corr-" + UUID.randomUUID());
    }

    private void send(DomainEvent e) throws Exception {
        kafka.send(e.topic(), e.key(), mapper.writeValueAsString(e)).get();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private JsonNode get(String path) {
        try {
            return json(call(HttpMethod.GET, path, null, TOKEN));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> call(HttpMethod method, String path, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, h), String.class);
    }

    private JsonNode json(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode().is2xxSuccessful()).as(r.getStatusCode() + " " + r.getBody()).isTrue();
        return mapper.readTree(r.getBody());
    }

    private <T> T await(Supplier<T> probe, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            T v = probe.get();
            if (v != null) {
                return v;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    /** The decoded text/html part of a multipart email. */
    private static String html(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof jakarta.mail.Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String found = html(mp.getBodyPart(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
