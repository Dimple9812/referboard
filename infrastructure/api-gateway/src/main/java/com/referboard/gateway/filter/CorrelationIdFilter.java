package com.referboard.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs first: accepts a sane {@code X-Correlation-Id} from the client or creates one, passes it to the service,
 * returns it on the response (also on 401/429/5xx from the gateway itself), and writes one access-log line.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private static final Logger access = LoggerFactory.getLogger("referboard.gateway.access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = sanitizeOrGenerate(exchange.getRequest().getHeaders().getFirst(HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
        ServerWebExchange ex = exchange.mutate().request(request).build();
        ex.getAttributes().put(ATTRIBUTE, id);
        ex.getResponse().getHeaders().set(HEADER, id);
        long start = System.nanoTime();
        return chain.filter(ex).doFinally(signal -> {
            String path = request.getPath().value();
            if (!path.startsWith("/actuator")) {
                HttpStatusCode status = ex.getResponse().getStatusCode();
                access.info("{} {} {} {}ms [{}]", request.getMethod(), path, status == null ? "-" : status.value(),
                        (System.nanoTime() - start) / 1_000_000, id);
            }
        });
    }

    static String sanitizeOrGenerate(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
