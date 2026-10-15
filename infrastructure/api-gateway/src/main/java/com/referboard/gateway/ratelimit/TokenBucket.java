package com.referboard.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis token bucket (one atomic Lua script per request). Fails open: if Redis is down or slow, requests are
 * allowed and a warning is logged at most once a minute. Rate limiting protects capacity; it must never become an
 * outage of its own.
 */
@Component
public class TokenBucket {

    private static final Logger log = LoggerFactory.getLogger(TokenBucket.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"),
            List.class);
    static final Duration REDIS_TIMEOUT = Duration.ofMillis(500);

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
        static Decision failOpen() {
            return new Decision(true, -1, 0);
        }
    }

    private final ReactiveStringRedisTemplate redis;
    private final AtomicLong lastWarning = new AtomicLong();

    public TokenBucket(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /** One request against a bucket refilling {@code perMinute} tokens per minute, holding at most that many. */
    public Mono<Decision> take(String key, int perMinute) {
        double ratePerSecond = perMinute / 60.0;
        return redis.execute(SCRIPT, List.of(key), List.of(Double.toString(ratePerSecond), Integer.toString(perMinute)))
                .next()
                .map(TokenBucket::toDecision)
                .timeout(REDIS_TIMEOUT)
                .onErrorResume(e -> {
                    long now = System.currentTimeMillis();
                    long last = lastWarning.get();
                    if (now - last > 60_000 && lastWarning.compareAndSet(last, now)) {
                        log.warn("Rate limiting unavailable (Redis: {}); allowing requests", e.toString());
                    }
                    return Mono.just(Decision.failOpen());
                })
                .defaultIfEmpty(Decision.failOpen());
    }

    @SuppressWarnings("rawtypes")
    private static Decision toDecision(List result) {
        long allowed = ((Number) result.get(0)).longValue();
        long remaining = ((Number) result.get(1)).longValue();
        long retry = ((Number) result.get(2)).longValue();
        return new Decision(allowed == 1, remaining, retry);
    }
}
