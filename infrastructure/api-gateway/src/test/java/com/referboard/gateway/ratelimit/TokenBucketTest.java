package com.referboard.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.test.StepVerifier;

import java.time.Duration;

class TokenBucketTest {

    @Test
    void failsOpenWhenRedisIsDown() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build());
        factory.afterPropertiesSet();
        try {
            TokenBucket bucket = new TokenBucket(new ReactiveStringRedisTemplate(factory));
            StepVerifier.create(bucket.take("rl:test:x", 5))
                    .expectNextMatches(d -> d.allowed() && d.remaining() == -1)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        } finally {
            factory.destroy();
        }
    }
}
