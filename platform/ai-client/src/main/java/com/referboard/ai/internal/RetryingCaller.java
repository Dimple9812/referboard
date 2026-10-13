package com.referboard.ai.internal;

import com.referboard.ai.LlmCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries transient provider failures: 429, 5xx (incl. Anthropic's 529 "overloaded") and network errors, with
 * exponential backoff + jitter, honouring {@code Retry-After} (capped). 4xx other than 429 fail immediately —
 * retrying a bad request or bad key only burns quota.
 */
public final class RetryingCaller {

    private static final Logger log = LoggerFactory.getLogger(RetryingCaller.class);
    private static final Duration MAX_WAIT = Duration.ofSeconds(20);

    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final int maxAttempts;
    private final Sleeper sleeper;

    public RetryingCaller(int maxAttempts) {
        this(maxAttempts, d -> Thread.sleep(d.toMillis()));
    }

    public RetryingCaller(int maxAttempts, Sleeper sleeper) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.sleeper = sleeper;
    }

    public <T> T call(String provider, Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                if (!retryable) {
                    throw new LlmCallException(provider + " rejected the request (HTTP " + status + "): "
                            + abbreviate(e.getResponseBodyAsString()), e);
                }
                last = e;
                backoff(provider, attempt, "HTTP " + status, retryAfter(e));
            } catch (ResourceAccessException e) {
                last = e;
                backoff(provider, attempt, e.getClass().getSimpleName(), null);
            }
        }
        throw new LlmCallException(provider + " failed after " + maxAttempts + " attempts", last);
    }

    private void backoff(String provider, int attempt, String reason, Duration retryAfter) {
        if (attempt >= maxAttempts) {
            return;
        }
        long base = (long) Math.min(MAX_WAIT.toMillis(), 1000L * Math.pow(2, attempt - 1));
        long wait = retryAfter != null ? Math.min(retryAfter.toMillis(), MAX_WAIT.toMillis())
                : base + ThreadLocalRandom.current().nextLong(0, 250);
        log.warn("{} call failed ({}), attempt {}/{}; retrying in {} ms", provider, reason, attempt, maxAttempts, wait);
        try {
            sleeper.sleep(Duration.ofMillis(wait));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("Interrupted while retrying " + provider, ie);
        }
    }

    private static Duration retryAfter(RestClientResponseException e) {
        if (e.getResponseHeaders() == null) {
            return null;
        }
        String value = e.getResponseHeaders().getFirst("retry-after");
        try {
            return value == null ? null : Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
