package com.referboard.ingestion.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.referboard.ingestion.source.SourceFetchException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Every outbound request goes through here:
 * <ol>
 *   <li><b>robots.txt</b> per host (cached 24h; 4xx = allow all, unreachable/5xx = disallow, per RFC 9309)</li>
 *   <li><b>per-host rate limit</b> (Resilience4j RateLimiter)</li>
 *   <li><b>retry with exponential backoff</b> on network errors, 429 and 5xx only (Resilience4j Retry)</li>
 * </ol>
 * 404 is surfaced immediately as {@link SourceFetchException.Kind#NOT_FOUND}: a missing board won't appear by
 * retrying.
 */
public class PoliteHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PoliteHttpClient.class);
    private static final Duration ROBOTS_TTL = Duration.ofHours(24);
    private static final Duration ROBOTS_ERROR_TTL = Duration.ofMinutes(10);

    private record CachedRobots(RobotsTxt robots, Instant expiresAt) {
    }

    /** 429 / 5xx — worth retrying. */
    static class TransientHttpException extends RuntimeException {
        TransientHttpException(String message) {
            super(message);
        }
    }

    private final WebClient web;
    private final String productToken;
    private final int requestsPerSecond;
    private final Retry retry;
    private final Clock clock;
    private final Map<String, CachedRobots> robotsCache = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public PoliteHttpClient(WebClient web, String userAgent, int requestsPerSecond, int maxAttempts,
                            Duration initialBackoff, Clock clock) {
        this.web = web;
        this.productToken = userAgent.split("[/ ]")[0];
        this.requestsPerSecond = Math.max(1, requestsPerSecond);
        this.clock = clock;
        this.retry = Retry.of("source-http", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialRandomBackoff(
                        initialBackoff, 2.0, 0.25))
                .retryOnException(e -> e instanceof TransientHttpException || e instanceof WebClientRequestException
                        || e instanceof TimeoutException)
                .build());
    }

    public Mono<JsonNode> getJson(URI uri) {
        return robots(uri).flatMap(robots -> {
            String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            if (!robots.isAllowed(pathAndQuery)) {
                return Mono.error(new SourceFetchException(SourceFetchException.Kind.BLOCKED_BY_ROBOTS,
                        robots.isUnreachable()
                                ? "robots.txt for " + uri.getHost() + " could not be read; not fetching until it can be"
                                : "robots.txt disallows " + uri.getHost() + uri.getRawPath()));
            }
            return web.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, r -> Mono.error(new SourceFetchException(
                            SourceFetchException.Kind.NOT_FOUND, "404 for " + redact(uri))))
                    .onStatus(PoliteHttpClient::transientStatus, r -> Mono.error(
                            new TransientHttpException("HTTP " + r.statusCode().value() + " for " + redact(uri))))
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(45))
                    .transformDeferred(RateLimiterOperator.of(limiter(uri.getHost())))
                    .transformDeferred(RetryOperator.of(retry))
                    .onErrorMap(e -> !(e instanceof SourceFetchException), e -> classify(e, uri));
        });
    }

    private Mono<RobotsTxt> robots(URI uri) {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        CachedRobots cached = robotsCache.get(origin);
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return Mono.just(cached.robots());
        }
        return web.get().uri(URI.create(origin + "/robots.txt"))
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    if (status.is2xxSuccessful()) {
                        return response.bodyToMono(String.class).defaultIfEmpty("")
                                .map(body -> RobotsTxt.parse(body, productToken))
                                .map(r -> cache(origin, r, ROBOTS_TTL));
                    }
                    if (status.is4xxClientError()) {
                        return response.releaseBody().thenReturn(cache(origin, RobotsTxt.allowAll(), ROBOTS_TTL));
                    }
                    log.warn("robots.txt for {} returned {}; treating host as disallowed for now", origin, status);
                    return response.releaseBody().thenReturn(cache(origin, RobotsTxt.unreachable(), ROBOTS_ERROR_TTL));
                })
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(e -> {
                    log.warn("robots.txt for {} unreachable ({}); treating host as disallowed for now", origin,
                            e.toString());
                    return Mono.just(cache(origin, RobotsTxt.unreachable(), ROBOTS_ERROR_TTL));
                });
    }

    private RobotsTxt cache(String origin, RobotsTxt robots, Duration ttl) {
        robotsCache.put(origin, new CachedRobots(robots, clock.instant().plus(ttl)));
        return robots;
    }

    private RateLimiter limiter(String host) {
        return limiters.computeIfAbsent(host, h -> RateLimiter.of("host-" + h, RateLimiterConfig.custom()
                .limitForPeriod(requestsPerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMinutes(2))
                .build()));
    }

    private static boolean transientStatus(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    private static SourceFetchException classify(Throwable e, URI uri) {
        if (e instanceof TransientHttpException || e instanceof WebClientRequestException
                || e instanceof TimeoutException || e instanceof RequestNotPermitted) {
            return new SourceFetchException(SourceFetchException.Kind.TRANSIENT, e.getMessage(), e);
        }
        if (e instanceof WebClientResponseException w) {
            return new SourceFetchException(SourceFetchException.Kind.FAILED,
                    "HTTP " + w.getStatusCode().value() + " for " + redact(uri), e);
        }
        return new SourceFetchException(SourceFetchException.Kind.FAILED,
                "Unreadable response from " + uri.getHost() + ": " + e.getMessage(), e);
    }

    /** Never log API keys passed as query parameters (Adzuna). */
    static String redact(URI uri) {
        String q = uri.getRawQuery();
        String safe = q == null ? "" : "?" + q.replaceAll("(?i)(app_key|app_id|api_key|key|token)=[^&]*", "$1=***");
        return uri.getScheme() + "://" + uri.getAuthority() + uri.getRawPath() + safe;
    }
}
