package com.referboard.common.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation-id constants and helpers shared by HTTP filters, Feign interceptors and Kafka consumers. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    // Accept caller-supplied ids only if they look sane; never echo arbitrary input into logs/headers.
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String sanitizeOrGenerate(String candidate) {
        if (candidate != null && VALID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
