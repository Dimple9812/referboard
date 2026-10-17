package com.referboard.billing.razorpay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignaturesTest {

    @Test
    void checkoutSignatureIsHmacOfOrderAndPayment() {
        // HMAC-SHA256("order_A|pay_B", "secret"), computed independently.
        String sig = RazorpaySignatures.hmac("order_A|pay_B", "secret");
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
        assertThat(RazorpaySignatures.validPayment("order_A", "pay_B", sig, "secret")).isTrue();
        assertThat(RazorpaySignatures.validPayment("order_A", "pay_B", sig.toUpperCase(), "secret")).isTrue();
        assertThat(RazorpaySignatures.validPayment("order_A", "pay_C", sig, "secret")).isFalse();
        assertThat(RazorpaySignatures.validPayment("order_A", "pay_B", sig, "other")).isFalse();
        assertThat(RazorpaySignatures.validPayment("order_A", "pay_B", sig, "")).as("no secret, nothing verifies").isFalse();
        assertThat(RazorpaySignatures.validPayment("order_A", "pay_B", null, "secret")).isFalse();
    }

    @Test
    void webhookSignatureCoversTheExactBody() {
        byte[] body = "{\"event\":\"order.paid\"}".getBytes(StandardCharsets.UTF_8);
        String sig = RazorpaySignatures.hmac(body, "whsec");
        assertThat(RazorpaySignatures.validWebhook(body, sig, "whsec")).isTrue();
        assertThat(RazorpaySignatures.validWebhook("{\"event\": \"order.paid\"}".getBytes(StandardCharsets.UTF_8), sig, "whsec"))
                .as("re-serialised JSON is a different body").isFalse();
        assertThat(RazorpaySignatures.validWebhook(body, sig, null)).isFalse();
    }
}
