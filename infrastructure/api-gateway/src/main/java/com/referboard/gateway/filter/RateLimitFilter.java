package com.referboard.gateway.filter;

import com.referboard.gateway.config.GatewayProperties;
import com.referboard.gateway.ratelimit.RateLimitPolicy;
import com.referboard.gateway.ratelimit.TokenBucket;
import com.referboard.gateway.web.ApiErrors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

/** Applies {@link RateLimitPolicy} to every routed request; over the limit → 429 with Retry-After. */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final TokenBucket bucket;
    private final GatewayProperties props;
    private final RemoteAddressResolver ipResolver;

    public RateLimitFilter(TokenBucket bucket, GatewayProperties props) {
        this.bucket = bucket;
        this.props = props;
        this.ipResolver = props.trustForwardedFor() ? XForwardedRemoteAddressResolver.maxTrustedIndex(1)
                : new RemoteAddressResolver() { };
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        RateLimitPolicy.Bucket b = RateLimitPolicy.classify(exchange.getRequest().getMethod(), path);
        int limit = RateLimitPolicy.perMinute(b, props.rateLimits());
        return exchange.getPrincipal()
                .map(RateLimitFilter::userId)
                .defaultIfEmpty("")
                .flatMap(user -> bucket.take(RateLimitPolicy.key(b, user.isEmpty() ? null : user, clientIp(exchange)),
                        limit))
                .flatMap(decision -> {
                    if (decision.remaining() >= 0) {
                        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                                Long.toString(decision.remaining()));
                    }
                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().getHeaders().set("Retry-After",
                            Long.toString(Math.max(1, decision.retryAfterSeconds())));
                    return ApiErrors.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            b == RateLimitPolicy.Bucket.AUTH
                                    ? "Too many sign-in attempts. Wait a minute and try again."
                                    : "Too many requests. Slow down and try again in a moment.");
                });
    }

    private static String userId(Principal p) {
        return p instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : "";
    }

    private String clientIp(ServerWebExchange exchange) {
        InetSocketAddress addr = ipResolver.resolve(exchange);
        if (addr == null) {
            return "unknown";
        }
        return addr.getAddress() != null ? addr.getAddress().getHostAddress() : addr.getHostString();
    }

    @Override
    public int getOrder() {
        return -1000;   // before routing, after security (a WebFilter) has set the principal
    }
}
