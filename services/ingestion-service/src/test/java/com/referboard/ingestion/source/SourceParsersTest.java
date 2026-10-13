package com.referboard.ingestion.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsers against fixtures shaped like each provider's documented public API response. */
class SourceParsersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static JsonNode fixture(String name) throws Exception {
        try (InputStream in = SourceParsersTest.class.getResourceAsStream("/fixtures/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    @Test
    void greenhouse() throws Exception {
        List<RawPosting> jobs = GreenhouseSource.parse(fixture("greenhouse-acmepay.json"),
                SourceTarget.board(SourceType.GREENHOUSE, "acmepay", "AcmePay"));

        assertThat(jobs).hasSize(4);
        RawPosting first = jobs.getFirst();
        assertThat(first.sourceKey()).isEqualTo("greenhouse:acmepay");
        assertThat(first.sourceJobId()).isEqualTo("4001");
        assertThat(first.companyName()).isEqualTo("AcmePay");
        assertThat(first.locationText()).isEqualTo("Bengaluru, India");
        assertThat(first.descriptionHtml()).as("entity-encoded HTML is decoded").startsWith("<p>Build payment rails");
        assertThat(first.descriptionText()).contains("• Kafka at scale");
        assertThat(first.department()).isEqualTo("Engineering");
        assertThat(first.applyUrl()).isEqualTo("https://boards.greenhouse.io/acmepay/jobs/4001");
        assertThat(first.postedAt()).as("first_published preferred over updated_at")
                .isEqualTo(java.time.Instant.parse("2026-09-20T14:00:00Z"));
    }

    @Test
    void lever() throws Exception {
        List<RawPosting> jobs = LeverSource.parse(fixture("lever-brightdata.json"),
                SourceTarget.board(SourceType.LEVER, "brightdata", "BrightData"));

        assertThat(jobs).hasSize(2);
        RawPosting data = jobs.getFirst();
        assertThat(data.title()).isEqualTo("Data Engineer");
        assertThat(data.remote()).isFalse();
        assertThat(data.countryHint()).isEqualTo("IN");
        assertThat(data.descriptionText()).contains("Spark").contains("Requirements").contains("• Python");
        assertThat(data.salaryMin()).isEqualTo(2_500_000L);
        assertThat(data.salaryCurrency()).isEqualTo("INR");
        assertThat(jobs.get(1).remote()).isTrue();
        assertThat(jobs.get(1).countryHint()).isEqualTo("US");
    }

    @Test
    void ashbySkipsUnlistedAndReadsCompensation() throws Exception {
        List<RawPosting> jobs = AshbySource.parse(fixture("ashby-nimbusai.json"),
                SourceTarget.board(SourceType.ASHBY, "nimbusai", "Nimbus AI"));

        assertThat(jobs).singleElement().satisfies(j -> {
            assertThat(j.title()).isEqualTo("Machine Learning Engineer");
            assertThat(j.remote()).isTrue();
            assertThat(j.countryHint()).isEqualTo("India");
            assertThat(j.salaryMin()).isEqualTo(4_000_000L);
            assertThat(j.salaryMax()).isEqualTo(6_000_000L);
            assertThat(j.salaryCurrency()).isEqualTo("INR");
            assertThat(j.descriptionText()).contains("PyTorch");
        });
    }

    @Test
    void adzunaSkipsAnonymousListingsAndTagsCountry() throws Exception {
        List<RawPosting> jobs = AdzunaSource.parse(fixture("adzuna-in.json"),
                new SourceTarget("adzuna:in:backend developer", SourceType.ADZUNA, "backend developer", "Adzuna"));

        assertThat(jobs).hasSize(2);
        assertThat(jobs.getFirst().companyName()).isEqualTo("AcmePay Technologies Private Limited");
        assertThat(jobs.getFirst().countryHint()).isEqualTo("IN");
        assertThat(jobs.getFirst().employmentType()).isEqualTo("full-time");
        assertThat(jobs.getFirst().salaryCurrency()).isEqualTo("INR");
        assertThat(jobs.get(1).salaryCurrency()).isNull();
    }
}
