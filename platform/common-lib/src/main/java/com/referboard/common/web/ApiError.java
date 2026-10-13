package com.referboard.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error body every ReferBoard service returns.
 *
 * @param code          stable machine-readable code (e.g. {@code EMAIL_TAKEN}); the frontend switches on this
 * @param message       human-readable, safe to show to users
 * @param correlationId same value as the {@code X-Correlation-Id} response header, for support/log lookup
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors
) {
    public record FieldViolation(String field, String message) {
    }
}
