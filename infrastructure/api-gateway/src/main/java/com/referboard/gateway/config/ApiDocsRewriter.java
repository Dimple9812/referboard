package com.referboard.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Services describe themselves with their own address; Swagger UI's "Try it out" must go through the gateway
 * (auth, CORS, rate limits). Rewrites the OpenAPI {@code servers} list to the gateway origin the browser used.
 */
final class ApiDocsRewriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiDocsRewriter() {
    }

    static Mono<String> pointAtGateway(ServerWebExchange exchange, String body) {
        if (body == null) {
            return Mono.empty();
        }
        try {
            ObjectNode doc = (ObjectNode) MAPPER.readTree(body);
            URI uri = exchange.getRequest().getURI();
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            doc.putArray("servers").addObject().put("url", origin).put("description", "ReferBoard API gateway");
            return Mono.just(MAPPER.writeValueAsString(doc));
        } catch (Exception e) {
            return Mono.just(body);   // not JSON (error page): pass through
        }
    }
}
