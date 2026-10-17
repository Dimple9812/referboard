package com.referboard.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.billing.config.BillingProperties;
import com.referboard.billing.razorpay.RazorpaySignatures;
import com.referboard.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Razorpay webhooks: the backstop for a checkout whose browser closed before it could confirm, and how refunds made
 * in the Razorpay dashboard reach ReferBoard. Each delivery is checked against the webhook secret and applied once
 * (by event id) in a single transaction, so a failure makes Razorpay retry rather than half-apply.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private final BillingProperties props;
    private final PaymentService payments;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public WebhookService(BillingProperties props, PaymentService payments, ObjectMapper mapper, JdbcTemplate jdbc,
                          TransactionTemplate tx) {
        this.props = props;
        this.payments = payments;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Outcome handle(byte[] body, String signature, String eventIdHeader) {
        if (!RazorpaySignatures.validWebhook(body, signature, props.razorpay().webhookSecret())) {
            log.warn("Rejected a Razorpay webhook with a bad or missing signature");
            throw ApiException.badRequest("INVALID_SIGNATURE", "Webhook signature doesn't match.");
        }
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_PAYLOAD", "Webhook body isn't JSON.");
        }
        String type = event.path("event").asText("");
        JsonNode payment = event.at("/payload/payment/entity");
        // Razorpay sends X-Razorpay-Event-Id; fall back to type + payment id so retries still dedupe.
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader
                : type + ":" + payment.path("id").asText("") + ":" + event.path("created_at").asText("");

        return tx.execute(s -> {
            int inserted = jdbc.update("insert into webhook_events (event_id, event_type, payload) values (?, ?, ?::jsonb) "
                    + "on conflict (event_id) do nothing", eventId, type, new String(body, StandardCharsets.UTF_8));
            if (inserted == 0) {
                return Outcome.DUPLICATE;
            }
            return switch (type) {
                case "payment.captured", "order.paid" -> {
                    String orderId = payment.path("order_id").asText(event.at("/payload/order/entity/id").asText(null));
                    boolean ours = orderId != null && payments.markPaid(orderId, payment.path("id").asText(),
                            payment.path("method").asText(null));
                    if (!ours) {
                        log.info("Webhook {} for an order ReferBoard didn't create ({}); ignored", type, orderId);
                    }
                    yield ours ? Outcome.APPLIED : Outcome.IGNORED;
                }
                case "payment.refunded" -> {
                    boolean full = "full".equals(payment.path("refund_status").asText())
                            || payment.path("amount_refunded").asLong() >= payment.path("amount").asLong(Long.MAX_VALUE);
                    if (!full) {
                        log.info("Partial refund on payment {}; Pro access unchanged", payment.path("id").asText());
                        yield Outcome.IGNORED;
                    }
                    yield payments.refund(payment.path("id").asText()) ? Outcome.APPLIED : Outcome.IGNORED;
                }
                default -> Outcome.IGNORED;
            };
        });
    }
}
