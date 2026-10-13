package com.referboard.analyzer.service;

import com.referboard.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.util.function.Supplier;

/** Feign connection failures/timeouts don't pass through the ErrorDecoder; make them a 502 like the rest. */
public final class Upstream {

    private static final Logger log = LoggerFactory.getLogger(Upstream.class);

    private Upstream() {
    }

    public static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (feign.FeignException e) {
            log.warn("Upstream call failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
                    "A dependent service is unavailable. Please try again shortly.");
        }
    }
}
