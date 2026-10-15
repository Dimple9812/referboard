package com.referboard.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.Buildable;
import org.springframework.cloud.gateway.route.builder.PredicateSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Function;

/**
 * One route per service by path prefix; services are found through Eureka ({@code lb://}) unless a URL is
 * configured. Only {@code /api/**}, the JWKS and the API docs are routed: services' actuator endpoints are never
 * exposed through the gateway. LLM-backed services get a longer response timeout.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration LLM_TIMEOUT = Duration.ofSeconds(120);

    /** Swagger docs name → service key. */
    static final String[][] DOCS = {{"auth", "auth"}, {"profile", "profile"}, {"jobs", "jobs"},
            {"ingestion", "ingestion"}, {"referral", "referral"}, {"analyzer", "analyzer"}, {"tracker", "tracker"},
            {"notification", "notification"}};

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder, GatewayProperties props) {
        RouteLocatorBuilder.Builder r = builder.routes()
                .route("auth", api(p -> p.path("/api/auth/**", "/.well-known/jwks.json"), props.service("auth"),
                        DEFAULT_TIMEOUT))
                .route("profile", api(p -> p.path("/api/profile/**"), props.service("profile"), LLM_TIMEOUT))
                .route("jobs", api(p -> p.path("/api/jobs/**"), props.service("jobs"), DEFAULT_TIMEOUT))
                .route("ingestion", api(p -> p.path("/api/ingestion/**"), props.service("ingestion"), DEFAULT_TIMEOUT))
                .route("referral", api(p -> p.path("/api/referrals/**"), props.service("referral"), LLM_TIMEOUT))
                .route("analyzer", api(p -> p.path("/api/analyses/**", "/api/resume-versions/**"),
                        props.service("analyzer"), LLM_TIMEOUT))
                .route("tracker", api(p -> p.path("/api/applications/**"), props.service("tracker"), DEFAULT_TIMEOUT))
                .route("notification", api(p -> p.path("/api/notifications/**"), props.service("notification"),
                        DEFAULT_TIMEOUT));
        for (String[] doc : DOCS) {
            String name = doc[0];
            r = r.route("docs-" + name, p -> p.path("/v3/api-docs/" + name)
                    .filters(f -> f.setPath("/v3/api-docs")
                            .modifyResponseBody(String.class, String.class, ApiDocsRewriter::pointAtGateway))
                    .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, DEFAULT_TIMEOUT.toMillis())
                    .uri(props.service(doc[1])));
        }
        return r.build();
    }

    private static Function<PredicateSpec, Buildable<org.springframework.cloud.gateway.route.Route>> api(
            Function<PredicateSpec, org.springframework.cloud.gateway.route.builder.BooleanSpec> predicate,
            String uri, Duration timeout) {
        return p -> predicate.apply(p)
                // The gateway sets CORS and the correlation id; drop duplicates when a service sets them too.
                .filters(f -> f.dedupeResponseHeader(
                        "Access-Control-Allow-Origin Access-Control-Allow-Credentials X-Correlation-Id", "RETAIN_FIRST"))
                .metadata(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, timeout.toMillis())
                .metadata(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR, 3000)
                .uri(uri);
    }
}
