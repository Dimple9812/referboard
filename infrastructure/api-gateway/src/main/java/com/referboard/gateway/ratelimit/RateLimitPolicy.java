package com.referboard.gateway.ratelimit;

import com.referboard.gateway.config.GatewayProperties;
import org.springframework.http.HttpMethod;

import java.util.regex.Pattern;

/**
 * Which bucket a request draws from:
 * <ul>
 *   <li>AUTH — sign-in, sign-up: per client IP, strict (credential stuffing). Refresh is not here: it needs an
 *       unguessable 256-bit cookie, runs on every page load, and a strict per-IP bucket would sign out
 *       everyone behind a shared office or campus IP;</li>
 *   <li>EXPENSIVE — calls that run an LLM (JD analysis, message drafts, resume upload/parse): per user;</li>
 *   <li>DEFAULT — every other API call: per user, or per IP when anonymous.</li>
 * </ul>
 */
public final class RateLimitPolicy {

    public enum Bucket { AUTH, EXPENSIVE, DEFAULT }

    private static final Pattern AUTH = Pattern.compile("^/api/auth/(login|signup|google)$");
    private static final Pattern EXPENSIVE = Pattern.compile(
            "^/api/analyses(/upload)?$|^/api/referrals/contacts/[^/]+/drafts$|^/api/profile/resumes(/[^/]+/reparse)?$");

    private RateLimitPolicy() {
    }

    public static Bucket classify(HttpMethod method, String path) {
        if (HttpMethod.POST.equals(method)) {
            if (AUTH.matcher(path).matches()) {
                return Bucket.AUTH;
            }
            if (EXPENSIVE.matcher(path).matches()) {
                return Bucket.EXPENSIVE;
            }
        }
        return Bucket.DEFAULT;
    }

    public static int perMinute(Bucket bucket, GatewayProperties.RateLimits limits) {
        return switch (bucket) {
            case AUTH -> limits.authPerMinute();
            case EXPENSIVE -> limits.expensivePerMinute();
            case DEFAULT -> limits.defaultPerMinute();
        };
    }

    /** AUTH is always keyed by IP (the caller isn't signed in yet); the rest by user when known. */
    public static String key(Bucket bucket, String userId, String clientIp) {
        String who = bucket != Bucket.AUTH && userId != null ? "u:" + userId : "ip:" + clientIp;
        return "rl:" + bucket.name().toLowerCase() + ":" + who;
    }
}
