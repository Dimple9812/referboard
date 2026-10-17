package com.referboard.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.billing.razorpay.RazorpaySignatures;
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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Billing over HTTP against real Postgres, embedded Kafka and a mock Razorpay API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "referboard.outbox.poll-interval-ms=200",
        "referboard.billing.razorpay.key-id=rzp_test_abc",
        "referboard.billing.razorpay.key-secret=" + BillingFlowIT.KEY_SECRET,
        "referboard.billing.razorpay.webhook-secret=" + BillingFlowIT.WEBHOOK_SECRET,
})
@EmbeddedKafka(partitions = 1, topics = Topics.PRO_STATUS_CHANGED, kraft = true,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingFlowIT {

    static final String KEY_SECRET = "key_secret_test";
    static final String WEBHOOK_SECRET = "webhook_secret_test";
    static final java.util.UUID USER = java.util.UUID.randomUUID();
    static final String TOKEN = TestJwt.tokenFor(USER);
    static final ObjectMapper MAPPER = new ObjectMapper();

    static MockWebServer razorpay;
    static Consumer<String, String> consumer;
    static final AtomicInteger orderSeq = new AtomicInteger();
    /** Razorpay order id → amount, and payment id → (order id, status), as the mock API knows them. */
    static final Map<String, Long> rzpOrders = new ConcurrentHashMap<>();
    static final Map<String, String[]> rzpPayments = new ConcurrentHashMap<>();
    static final List<RecordedRequest> calls = new CopyOnWriteArrayList<>();
    static final Map<String, String> ids = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Jwt {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) throws IOException {
        TestPostgres.register(registry, "billing");
        razorpay = new MockWebServer();
        razorpay.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                calls.add(r);
                try {
                    String path = r.getPath();
                    if (r.getMethod().equals("POST") && path.equals("/v1/orders")) {
                        JsonNode body = MAPPER.readTree(r.getBody().readUtf8());
                        String id = "order_T" + orderSeq.incrementAndGet();
                        rzpOrders.put(id, body.get("amount").asLong());
                        return json(Map.of("id", id, "amount", body.get("amount").asLong(), "currency", "INR",
                                "receipt", body.get("receipt").asText(), "status", "created"));
                    }
                    if (path.startsWith("/v1/payments/") && path.endsWith("/capture")) {
                        String id = path.split("/")[3];
                        rzpPayments.get(id)[1] = "captured";
                        return payment(id);
                    }
                    if (path.startsWith("/v1/payments/")) {
                        return payment(path.substring("/v1/payments/".length()));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new MockResponse().setResponseCode(404);
            }

            private MockResponse payment(String id) throws IOException {
                String[] p = rzpPayments.get(id);
                if (p == null) {
                    return new MockResponse().setResponseCode(400).setBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\"}}");
                }
                return json(Map.of("id", id, "order_id", p[0], "amount", rzpOrders.get(p[0]), "currency", "INR",
                        "status", p[1], "method", "upi"));
            }
        });
        razorpay.start();
        registry.add("referboard.billing.razorpay.api-base-url", () -> "http://localhost:" + razorpay.getPort());
    }

    @BeforeAll
    static void subscribe(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("billing-it-" + java.util.UUID.randomUUID(), "true", broker);
        props.put("auto.offset.reset", "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, Topics.PRO_STATUS_CHANGED);
    }

    @AfterAll
    static void close() throws IOException {
        consumer.close();
        razorpay.shutdown();
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @Order(1)
    void plansArePublicAndCheckoutIsOffered() throws Exception {
        JsonNode plans = json(rest.getForEntity("/api/billing/plans", String.class), 200);
        assertThat(plans.get("available").asBoolean()).isTrue();
        assertThat(plans.get("testMode").asBoolean()).isTrue();
        assertThat(plans.at("/plans/0/id").asText()).isEqualTo("PRO_MONTH");
        assertThat(plans.at("/plans/0/amountPaise").asLong()).isEqualTo(29900);
        assertThat(plans.at("/plans/1/days").asInt()).isEqualTo(365);
        assertThat(json(get("/api/billing/me", TOKEN), 200).get("plan").asText()).isEqualTo("FREE");
        assertThat(get("/api/billing/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(2)
    void aCheckoutPaymentIsVerifiedOnceAndStartsPro() throws Exception {
        JsonNode checkout = json(post("/api/billing/orders", Map.of("plan", "PRO_MONTH"), TOKEN), 201);
        assertThat(checkout.get("keyId").asText()).isEqualTo("rzp_test_abc");
        assertThat(checkout.get("amountPaise").asLong()).isEqualTo(29900);
        String rzpOrder = checkout.get("razorpayOrderId").asText();
        RecordedRequest create = calls.stream().filter(c -> c.getPath().equals("/v1/orders")).findFirst().orElseThrow();
        assertThat(create.getHeader("Authorization")).isEqualTo("Basic " + java.util.Base64.getEncoder()
                .encodeToString(("rzp_test_abc:" + KEY_SECRET).getBytes()));
        assertThat(rzpOrders.get(checkout.get("razorpayOrderId").asText())).as("amount sent to Razorpay").isEqualTo(29900L);

        rzpPayments.put("pay_1", new String[]{rzpOrder, "captured"});
        String orderId = checkout.get("orderId").asText();
        ResponseEntity<String> forged = post("/api/billing/orders/" + orderId + "/verify",
                Map.of("razorpayPaymentId", "pay_1", "razorpaySignature", "0".repeat(64)), TOKEN);
        assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(forged.getBody()).contains("INVALID_SIGNATURE");
        assertThat(json(get("/api/billing/me", TOKEN), 200).get("plan").asText()).isEqualTo("FREE");

        Map<String, String> good = Map.of("razorpayPaymentId", "pay_1",
                "razorpaySignature", RazorpaySignatures.hmac(rzpOrder + "|pay_1", KEY_SECRET));
        JsonNode status = json(post("/api/billing/orders/" + orderId + "/verify", good, TOKEN), 200);
        assertThat(status.get("plan").asText()).isEqualTo("PRO");
        assertThat(status.get("daysLeft").asInt()).isEqualTo(30);
        Instant until = Instant.parse(status.get("proUntil").asText());
        assertThat(until).isCloseTo(Instant.now().plus(30, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
        assertThat(status.at("/orders/0/status").asText()).isEqualTo("PAID");
        assertThat(status.at("/orders/0/method").asText()).isEqualTo("upi");

        // The same confirmation again (double click, retry) changes nothing.
        JsonNode again = json(post("/api/billing/orders/" + orderId + "/verify", good, TOKEN), 200);
        assertThat(again.get("proUntil").asText()).isEqualTo(status.get("proUntil").asText());

        JsonNode event = awaitEvents(1).getFirst();
        assertThat(event.get("userId").asText()).isEqualTo(USER.toString());
        assertThat(event.get("reason").asText()).isEqualTo("PURCHASE");
        assertThat(event.get("amountPaise").asLong()).isEqualTo(29900);
        assertThat(Instant.parse(event.get("proUntil").asText())).isEqualTo(until);
        ids.put("monthUntil", until.toString());

        // Someone else can't confirm (or see) my order.
        assertThat(post("/api/billing/orders/" + orderId + "/verify", good, TestJwt.tokenFor(java.util.UUID.randomUUID()))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(3)
    void buyingWhileProStacksAndAuthorisedPaymentsAreCaptured() throws Exception {
        JsonNode checkout = json(post("/api/billing/orders", Map.of("plan", "PRO_YEAR"), TOKEN), 201);
        String rzpOrder = checkout.get("razorpayOrderId").asText();
        rzpPayments.put("pay_2", new String[]{rzpOrder, "authorized"});
        JsonNode status = json(post("/api/billing/orders/" + checkout.get("orderId").asText() + "/verify",
                Map.of("razorpayPaymentId", "pay_2", "razorpaySignature", RazorpaySignatures.hmac(rzpOrder + "|pay_2", KEY_SECRET)),
                TOKEN), 200);
        assertThat(Instant.parse(status.get("proUntil").asText()))
                .isEqualTo(Instant.parse(ids.get("monthUntil")).plus(365, ChronoUnit.DAYS));
        assertThat(calls).anyMatch(c -> c.getPath().equals("/v1/payments/pay_2/capture"));
        assertThat(status.get("orders")).hasSize(2);
        ids.put("yearUntil", status.get("proUntil").asText());
        awaitEvents(1);
    }

    @Test
    @Order(4)
    void webhooksConfirmAbandonedCheckoutsOnceAndRejectForgeries() throws Exception {
        java.util.UUID other = java.util.UUID.randomUUID();
        String otherToken = TestJwt.tokenFor(other);
        JsonNode checkout = json(post("/api/billing/orders", Map.of("plan", "PRO_MONTH"), otherToken), 201);
        String rzpOrder = checkout.get("razorpayOrderId").asText();
        // The browser closed before verify: only Razorpay's webhook arrives.
        String body = MAPPER.writeValueAsString(Map.of("event", "order.paid", "created_at", 1, "payload", Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_3", "order_id", rzpOrder, "amount", 29900, "method", "card")),
                "order", Map.of("entity", Map.of("id", rzpOrder)))));
        assertThat(webhook(body, "deadbeef", "evt_1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(get("/api/billing/me", otherToken), 200).get("plan").asText()).isEqualTo("FREE");

        String sig = RazorpaySignatures.hmac(body.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET);
        assertThat(json(webhook(body, sig, "evt_1"), 200).get("result").asText()).isEqualTo("APPLIED");
        assertThat(json(webhook(body, sig, "evt_1"), 200).get("result").asText()).isEqualTo("DUPLICATE");
        // payment.captured for the same payment, a different delivery: still applied once.
        String captured = body.replace("order.paid", "payment.captured");
        assertThat(json(webhook(captured, RazorpaySignatures.hmac(captured.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET),
                "evt_2"), 200).get("result").asText()).isEqualTo("APPLIED");
        JsonNode status = json(get("/api/billing/me", otherToken), 200);
        assertThat(status.get("plan").asText()).isEqualTo("PRO");
        assertThat(status.get("daysLeft").asInt()).isEqualTo(30);
        assertThat(status.at("/orders/0/method").asText()).isEqualTo("card");
        assertThat(awaitEvents(1).getFirst().get("userId").asText()).isEqualTo(other.toString());

        // An order this ReferBoard never created (another product on the same Razorpay account) is ignored.
        String foreign = body.replace(rzpOrder, "order_SOMETHING_ELSE").replace("pay_3", "pay_9");
        assertThat(json(webhook(foreign, RazorpaySignatures.hmac(foreign.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET),
                "evt_3"), 200).get("result").asText()).isEqualTo("IGNORED");
    }

    @Test
    @Order(5)
    void aFullRefundTakesBackThatOrdersDaysOnly() throws Exception {
        String partial = MAPPER.writeValueAsString(Map.of("event", "payment.refunded", "payload", Map.of("payment",
                Map.of("entity", Map.of("id", "pay_2", "amount", 249900, "amount_refunded", 10000, "refund_status", "partial")))));
        assertThat(json(webhook(partial, RazorpaySignatures.hmac(partial.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET),
                "evt_4"), 200).get("result").asText()).isEqualTo("IGNORED");

        String full = partial.replace("10000", "249900").replace("partial", "full");
        assertThat(json(webhook(full, RazorpaySignatures.hmac(full.getBytes(StandardCharsets.UTF_8), WEBHOOK_SECRET),
                "evt_5"), 200).get("result").asText()).isEqualTo("APPLIED");
        JsonNode status = json(get("/api/billing/me", TOKEN), 200);
        assertThat(status.get("proUntil").asText()).isEqualTo(ids.get("monthUntil"));
        assertThat(status.at("/orders/0/status").asText()).isEqualTo("REFUNDED");
        JsonNode event = awaitEvents(1).getFirst();
        assertThat(event.get("reason").asText()).isEqualTo("REFUND");
        assertThat(event.get("amountPaise").asLong()).isEqualTo(-249900);
    }

    @Test
    @Order(6)
    void badInput() {
        assertThat(post("/api/billing/orders", Map.of("plan", "PRO_FOREVER"), TOKEN).getBody()).contains("UNKNOWN_PLAN");
        assertThat(post("/api/billing/orders", Map.of("plan", "PRO_MONTH"), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------------------------------------------------

    private List<JsonNode> awaitEvents(int n) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (out.size() < n && System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500))) {
                out.add(MAPPER.readTree(r.value()));
            }
        }
        assertThat(out).hasSize(n);
        return out;
    }

    private ResponseEntity<String> webhook(String body, String signature, String eventId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Razorpay-Signature", signature);
        h.add("X-Razorpay-Event-Id", eventId);
        return rest.exchange("/api/billing/webhooks/razorpay", HttpMethod.POST,
                new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), h), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private static JsonNode json(ResponseEntity<String> r, int expected) throws IOException {
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(expected);
        return MAPPER.readTree(r.getBody());
    }

    private static MockResponse json(Object body) throws IOException {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(MAPPER.writeValueAsString(body));
    }
}
