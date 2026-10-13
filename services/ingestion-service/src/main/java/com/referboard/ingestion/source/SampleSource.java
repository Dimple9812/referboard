package com.referboard.ingestion.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.normalize.Html;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.referboard.ingestion.source.GreenhouseSource.text;

/**
 * Offline demo data ({@code seed/sample-jobs.json}) so the platform works end-to-end with no network or API keys.
 * Companies are fictional and every description says it's sample data. Enable with {@code INGESTION_SAMPLE_DATA=true};
 * never enable in production.
 */
@Component
public class SampleSource implements JobSource {

    public static final SourceTarget TARGET = new SourceTarget("sample:demo", SourceType.SAMPLE, "demo",
            "Sample jobs (fictional companies)");

    private final IngestionProperties.Sample config;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SampleSource(IngestionProperties props, ObjectMapper mapper, Clock clock) {
        this.config = props.sample();
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public SourceType type() {
        return SourceType.SAMPLE;
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Mono<List<RawPosting>> fetch(SourceTarget target) {
        return Mono.fromCallable(this::load);
    }

    List<RawPosting> load() {
        try (InputStream in = new ClassPathResource("seed/sample-jobs.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            List<RawPosting> out = new ArrayList<>();
            for (JsonNode job : root.path("jobs")) {
                String html = text(job, "descriptionHtml")
                        + "<p><em>Sample listing for local development — not a real job.</em></p>";
                out.add(new RawPosting("sample", TARGET.sourceKey(), job.path("id").asText(),
                        text(job, "company"), null, text(job, "title"), text(job, "location"),
                        job.has("remote") ? job.path("remote").asBoolean() : null, text(job, "country"),
                        text(job, "employmentType"), text(job, "department"), html, Html.toText(html),
                        "https://example.com/jobs/" + job.path("id").asText(),
                        clock.instant().minus(Duration.ofDays(job.path("postedDaysAgo").asInt(1))),
                        job.path("salaryMin").canConvertToLong() ? job.path("salaryMin").asLong() : null,
                        job.path("salaryMax").canConvertToLong() ? job.path("salaryMax").asLong() : null,
                        job.has("salaryMin") ? "INR" : null));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
