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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.referboard.ingestion.source.GreenhouseSource.text;

/** Lever Postings API: {@code GET /v0/postings/{company}?mode=json}. Public, no auth. */
@Component
public class LeverSource implements JobSource {

    private final PoliteHttpClient http;
    private final IngestionProperties.Board config;

    public LeverSource(PoliteHttpClient http, IngestionProperties props) {
        this.http = http;
        this.config = props.lever();
    }

    @Override
    public SourceType type() {
        return SourceType.LEVER;
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Mono<List<RawPosting>> fetch(SourceTarget target) {
        URI uri = UriComponentsBuilder.fromUriString(config.baseUrl())
                .pathSegment("v0", "postings", target.slug())
                .queryParam("mode", "json")
                .build().encode().toUri();
        return http.getJson(uri).map(body -> parse(body, target));
    }

    static List<RawPosting> parse(JsonNode body, SourceTarget target) {
        List<RawPosting> out = new ArrayList<>();
        for (JsonNode job : body) {
            JsonNode categories = job.path("categories");
            StringBuilder html = new StringBuilder();
            appendIfPresent(html, text(job, "description"));
            for (JsonNode list : job.path("lists")) {
                html.append("<h3>").append(org.jsoup.parser.Parser.unescapeEntities(
                        list.path("text").asText(""), false)).append("</h3><ul>")
                        .append(list.path("content").asText("")).append("</ul>");
            }
            appendIfPresent(html, text(job, "additional"));

            String workplace = text(job, "workplaceType");
            Boolean remote = workplace == null ? null : workplace.toLowerCase(Locale.ROOT).equals("remote");
            String location = text(categories, "location");
            if (location == null && categories.path("allLocations").isArray() && !categories.path("allLocations").isEmpty()) {
                location = categories.path("allLocations").get(0).asText();
            }
            JsonNode salary = job.path("salaryRange");
            out.add(new RawPosting(
                    "lever", target.sourceKey(), job.path("id").asText(),
                    target.displayName(), target.slug(),
                    text(job, "text"),
                    location,
                    remote,
                    text(job, "country"),
                    text(categories, "commitment"),
                    text(categories, "team"),
                    html.toString(), Html.toText(html.toString()),
                    text(job, "hostedUrl"),
                    job.path("createdAt").canConvertToLong() ? Instant.ofEpochMilli(job.path("createdAt").asLong()) : null,
                    salary.path("min").canConvertToLong() ? salary.path("min").asLong() : null,
                    salary.path("max").canConvertToLong() ? salary.path("max").asLong() : null,
                    text(salary, "currency")));
        }
        return out;
    }

    private static void appendIfPresent(StringBuilder sb, String html) {
        if (html != null) {
            sb.append(html);
        }
    }
}
