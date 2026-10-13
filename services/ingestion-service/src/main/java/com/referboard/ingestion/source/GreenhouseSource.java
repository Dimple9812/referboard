package com.referboard.ingestion.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.http.PoliteHttpClient;
import com.referboard.ingestion.normalize.Html;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Greenhouse Job Board API: {@code GET /v1/boards/{token}/jobs?content=true}. Public, no auth. */
@Component
public class GreenhouseSource implements JobSource {

    private final PoliteHttpClient http;
    private final IngestionProperties.Board config;

    public GreenhouseSource(PoliteHttpClient http, IngestionProperties props) {
        this.http = http;
        this.config = props.greenhouse();
    }

    @Override
    public SourceType type() {
        return SourceType.GREENHOUSE;
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Mono<List<RawPosting>> fetch(SourceTarget target) {
        URI uri = UriComponentsBuilder.fromUriString(config.baseUrl())
                .pathSegment("v1", "boards", target.slug(), "jobs")
                .queryParam("content", "true")
                .build().encode().toUri();
        return http.getJson(uri).map(body -> parse(body, target));
    }

    static List<RawPosting> parse(JsonNode body, SourceTarget target) {
        List<RawPosting> out = new ArrayList<>();
        for (JsonNode job : body.path("jobs")) {
            String html = Html.unescapeIfEncoded(text(job, "content"));
            List<String> departments = new ArrayList<>();
            job.path("departments").forEach(d -> departments.add(d.path("name").asText()));
            out.add(new RawPosting(
                    "greenhouse", target.sourceKey(), job.path("id").asText(),
                    target.displayName(), target.slug(),
                    text(job, "title"),
                    text(job.path("location"), "name"),
                    null, null, null,
                    departments.isEmpty() ? null : departments.getFirst(),
                    html, Html.toText(html),
                    text(job, "absolute_url"),
                    firstInstant(text(job, "first_published"), text(job, "updated_at")),
                    null, null, null));
        }
        return out;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    static Instant firstInstant(String... values) {
        for (String v : values) {
            if (v != null) {
                try {
                    return OffsetDateTime.parse(v).toInstant();
                } catch (Exception ignored) {
                    // try next
                }
            }
        }
        return null;
    }
}
