package com.referboard.gateway.web;

import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;

/**
 * Gateway-level failures as JSON: unknown path → 404, service down, unresolvable or not registered → 503, too slow →
 * 504. Downstream error responses are passed through untouched (they are already in this format).
 */
@Component
@Order(-2)   // before Spring Boot's default handler
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        Mapped m = map(ex);
        if (m.status().is5xxServerError()) {
            log.warn("{} {} failed: {} ({})", exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                    m.code(), ex.toString());
        }
        return ApiErrors.write(exchange, m.status(), m.code(), m.message());
    }

    record Mapped(HttpStatus status, String code, String message) {
    }

    static Mapped map(Throwable ex) {
        if (ex instanceof NotFoundException || hasCause(ex, ConnectException.class)
                || hasCause(ex, ConnectTimeoutException.class) || hasCause(ex, java.net.UnknownHostException.class)) {
            return new Mapped(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "This part of ReferBoard is temporarily unavailable. Please try again shortly.");
        }
        if (ex instanceof TimeoutException || hasCause(ex, java.util.concurrent.TimeoutException.class)) {
            return new Mapped(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "That took too long. Please try again.");
        }
        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode code = rse.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status == HttpStatus.NOT_FOUND) {
                return new Mapped(status, "NOT_FOUND", "No such endpoint");
            }
            if (status != null) {
                return new Mapped(status, status.name(), rse.getReason() == null ? status.getReasonPhrase()
                        : rse.getReason());
            }
        }
        return new Mapped(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Quote the correlation id if you contact support.");
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
