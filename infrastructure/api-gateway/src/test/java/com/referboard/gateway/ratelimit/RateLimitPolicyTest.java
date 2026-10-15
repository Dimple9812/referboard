package com.referboard.gateway.ratelimit;

import com.referboard.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static com.referboard.gateway.ratelimit.RateLimitPolicy.Bucket.AUTH;
import static com.referboard.gateway.ratelimit.RateLimitPolicy.Bucket.DEFAULT;
import static com.referboard.gateway.ratelimit.RateLimitPolicy.Bucket.EXPENSIVE;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPolicyTest {

    @Test
    void classifiesBuckets() {
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/auth/login")).isEqualTo(AUTH);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/auth/refresh")).as("page loads must not drain the sign-in bucket").isEqualTo(DEFAULT);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/auth/logout")).isEqualTo(DEFAULT);
        assertThat(RateLimitPolicy.classify(HttpMethod.GET, "/api/auth/me")).isEqualTo(DEFAULT);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/analyses")).isEqualTo(EXPENSIVE);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/analyses/upload")).isEqualTo(EXPENSIVE);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/analyses/abc/resume")).isEqualTo(DEFAULT);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/analyses/abc/cover-letters")).isEqualTo(EXPENSIVE);
        assertThat(RateLimitPolicy.classify(HttpMethod.PUT, "/api/cover-letters/abc")).isEqualTo(DEFAULT);
        assertThat(RateLimitPolicy.classify(HttpMethod.GET, "/api/analyses")).isEqualTo(DEFAULT);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/referrals/contacts/123/drafts")).isEqualTo(EXPENSIVE);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/profile/resumes")).isEqualTo(EXPENSIVE);
        assertThat(RateLimitPolicy.classify(HttpMethod.POST, "/api/profile/resumes/1/reparse")).isEqualTo(EXPENSIVE);
    }

    @Test
    void keysByUserExceptForSignIn() {
        assertThat(RateLimitPolicy.key(DEFAULT, "u1", "10.0.0.1")).isEqualTo("rl:default:u:u1");
        assertThat(RateLimitPolicy.key(DEFAULT, null, "10.0.0.1")).isEqualTo("rl:default:ip:10.0.0.1");
        assertThat(RateLimitPolicy.key(AUTH, "u1", "10.0.0.1")).isEqualTo("rl:auth:ip:10.0.0.1");
        var limits = new GatewayProperties.RateLimits(120, 10, 7);
        assertThat(RateLimitPolicy.perMinute(EXPENSIVE, limits)).isEqualTo(7);
    }
}
