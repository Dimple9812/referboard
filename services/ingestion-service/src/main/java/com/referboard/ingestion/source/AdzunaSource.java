package com.referboard.ingestion.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.http.PoliteHttpClient;
import com.referboard.ingestion.normalize.Html;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.referboard.ingestion.source.GreenhouseSource.firstInstant;
import static com.referboard.ingestion.source.GreenhouseSource.text;

/**
 * Adzuna search API (keys from env). One target per configured query; pages are fetched sequentially and stop
 * early when a page comes back short. Adzuna only returns a description snippet, so an ATS copy of the same job
 * (same de-dup key) takes precedence.
 */
@Component
public class AdzunaSource implements JobSource {

    private final PoliteHttpClient http;
    private final IngestionProperties.Adzuna config;

    public AdzunaSource(PoliteHttpClient http, IngestionProperties props) {
        this.http = http;
        this.config = props.adzuna();
    }

    @Override
    public SourceType type() {
        return SourceType.ADZUNA;
    }

    @Override
    public boolean enabled() {
        return config.configured();
    }

    public List<SourceTarget> targets() {
        return config.queries().stream()
                .map(q -> new SourceTarget("adzuna:" + config.country() + ":" + q.toLowerCase(Locale.ROOT),
                        SourceType.ADZUNA, q, "Adzuna " + config.country().toUpperCase(Locale.ROOT) + ": " + q))
                .toList();
    }

    @Override
    public Mono<List<RawPosting>> fetch(SourceTarget target) {
        return Flux.range(1, Math.max(1, config.pagesPerQuery()))
                .concatMap(page -> http.getJson(pageUri(target.slug(), page)).map(body -> parse(body, target)))
                .takeUntil(page -> page.size() < config.resultsPerPage())
                .collectList()
                .map(pages -> pages.stream().flatMap(List::stream).toList());
    }

    private URI pageUri(String query, int page) {
        return UriComponentsBuilder.fromUriString(config.baseUrl())
                .pathSegment("v1", "api", "jobs", config.country(), "search", String.valueOf(page))
                .queryParam("app_id", config.appId())
                .queryParam("app_key", config.appKey())
                .queryParam("results_per_page", config.resultsPerPage())
                .queryParam("what", query)
                .queryParam("max_days_old", config.maxDaysOld())
                .queryParam("sort_by", "date")
                .queryParam("content-type", "application/json")
                .build().encode().toUri();
    }

    static List<RawPosting> parse(JsonNode body, SourceTarget target) {
        List<RawPosting> out = new ArrayList<>();
        // Queries are country-scoped (adzuna:<country>:<query>), so every result is in that country.
        String country = target.sourceKey().split(":")[1].toUpperCase(Locale.ROOT);
        for (JsonNode job : body.path("results")) {
            String company = text(job.path("company"), "display_name");
            if (company == null) {
                continue; // anonymous agency listings can't be referred into
            }
            String snippet = text(job, "description");
            String contract = text(job, "contract_time");
            out.add(new RawPosting(
                    "adzuna", target.sourceKey(), job.path("id").asText(),
                    company, null,
                    org.jsoup.parser.Parser.unescapeEntities(text(job, "title"), false),
                    text(job.path("location"), "display_name"),
                    null, country,
                    contract == null ? null : contract.replace('_', '-'),
                    text(job.path("category"), "label"),
                    snippet == null ? null : "<p>" + org.jsoup.nodes.Entities.escape(snippet) + "</p>",
                    snippet == null ? "" : Html.toText(snippet),
                    text(job, "redirect_url"),
                    firstInstant(text(job, "created")),
                    job.path("salary_min").canConvertToLong() ? job.path("salary_min").asLong() : null,
                    job.path("salary_max").canConvertToLong() ? job.path("salary_max").asLong() : null,
                    job.has("salary_min") && "IN".equals(country) ? "INR" : null));
        }
        return out;
    }
}
