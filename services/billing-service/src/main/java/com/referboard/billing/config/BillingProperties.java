package com.referboard.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "referboard.billing")
public record BillingProperties(
        Razorpay razorpay,
        @DefaultValue("ReferBoard") String businessName,
        Map<String, Plan> plans,
        @DefaultValue("PT2H") Duration orderTtl
) {
    public record Razorpay(String keyId, String keySecret, String webhookSecret,
                           @DefaultValue("https://api.razorpay.com") String apiBaseUrl) {
        /** Both API keys present: checkout can be offered. */
        public boolean configured() {
            return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
        }

        public boolean testMode() {
            return keyId != null && keyId.startsWith("rzp_test_");
        }
    }

    public record Plan(String name, long amountPaise, int days) {
    }
}
