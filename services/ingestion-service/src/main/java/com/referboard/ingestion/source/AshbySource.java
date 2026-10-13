package com.referboard.ingestion.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.http.PoliteHttpClient;
import com.referboard.ingestion.normalize.Html;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static com.referboard.ingestion.source.GreenhouseSource.firstInstant;
import static com.referboard.ingestion.source.GreenhouseSource.text;

/** Ashby public Job Posting API: {@code GET /posting-api/job-board/{name}?includeCompensation=true}. */
@Component
public class AshbySource implements JobSource {

    private final PoliteHttpClient http;
    private final IngestionProperties.Board config;

    public AshbySource(PoliteHttpClient http, IngestionProperties props) {
        this.http = http;
        this.config = props.ashby();
    }

    @Override
    public SourceType type() {
        return SourceType.ASHBY;
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Mono<List<RawPosting>> fetch(SourceTarget target) {
        URI uri = UriComponentsBuilder.fromUriString(config.baseUrl())
                .pathSegment("posting-api", "job-board", target.slug())
                .queryParam("includeCompensation", "true")
                .build().encode().toUri();
        return http.getJson(uri).map(body -> parse(body, target));
    }

    static List<RawPosting> parse(JsonNode body, SourceTarget target) {
        List<RawPosting> out = new ArrayList<>();
        for (JsonNode job : body.path("jobs")) {
            if (job.has("isListed") && !job.path("isListed").asBoolean(true)) {
                continue;
            }
            String html = text(job, "descriptionHtml");
            String plain = text(job, "descriptionPlain");
            Boolean remote = job.has("isRemote") ? job.path("isRemote").asBoolean() : null;
            if (remote == null && "Remote".equalsIgnoreCase(text(job, "workplaceType"))) {
                remote = true;
            }
            String location = text(job, "location");
            JsonNode address = job.path("address").path("postalAddress");
            Long[] salary = compensation(job.path("compensation"));
            out.add(new RawPosting(
                    "ashby", target.sourceKey(), job.path("id").asText(),
                    target.displayName(), target.slug(),
                    text(job, "title"),
                    location,
                    remote,
                    text(address, "addressCountry"),
                    text(job, "employmentType"),
                    text(job, "department"),
                    html, plain != null ? plain : Html.toText(html),
                    text(job, "jobUrl"),
                    firstInstant(text(job, "publishedAt")),
                    salary[0], salary[1],
                    salary[0] == null ? null : text(firstSalaryComponent(job.path("compensation")), "currencyCode")));
        }
        return out;
    }

    /** Ashby nests salary ranges under compensation tiers; take the first salary component if present. */
    private static Long[] compensation(JsonNode compensation) {
        JsonNode c = firstSalaryComponent(compensation);
        if (c == null) {
            return new Long[]{null, null};
        }
        Long min = c.path("minValue").canConvertToLong() ? c.path("minValue").asLong() : null;
        Long max = c.path("maxValue").canConvertToLong() ? c.path("maxValue").asLong() : null;
        return new Long[]{min, max};
    }

    private static JsonNode firstSalaryComponent(JsonNode compensation) {
        for (JsonNode tier : compensation.path("compensationTiers")) {
            for (JsonNode component : tier.path("components")) {
                if ("Salary".equalsIgnoreCase(component.path("compensationType").asText())) {
                    return component;
                }
            }
        }
        return null;
    }
}
