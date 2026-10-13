package com.referboard.common.web;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Service-to-service calls made while handling a user request carry that user's bearer token (so the callee
 * enforces the same ownership rules) and the correlation id (so logs line up across services).
 */
public class ForwardingFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String correlationId = CorrelationId.current();
        if (correlationId != null && !template.headers().containsKey(CorrelationId.HEADER)) {
            template.header(CorrelationId.HEADER, correlationId);
        }
        if (template.headers().containsKey(HttpHeaders.AUTHORIZATION)) {
            return;
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.startsWith("Bearer ")) {
                template.header(HttpHeaders.AUTHORIZATION, auth);
            }
        }
    }
}
