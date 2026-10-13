package com.referboard.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "referboard.ingestion")
public record IngestionProperties(
        @DefaultValue("0 0 */6 * * *") String cron,
        @DefaultValue("false") boolean runOnStartup,
        @DefaultValue("ReferBoardBot/0.1") String userAgent,
        @DefaultValue("classpath:companies.yml") String seedFile,
        @DefaultValue("4") int maxConcurrentSources,
        @DefaultValue("2") int requestsPerSecondPerHost,
        @DefaultValue("4") int retryMaxAttempts,
        @DefaultValue("PT2S") Duration retryInitialBackoff,
        @DefaultValue("P7D") Duration closeAfter,
        @DefaultValue("P60D") Duration ghostAfter,
        @DefaultValue("P7D") Duration retryNotFoundAfter,
        @DefaultValue Filters filters,
        @DefaultValue Board greenhouse,
        @DefaultValue Board lever,
        @DefaultValue Board ashby,
        @DefaultValue Adzuna adzuna,
        @DefaultValue Sample sample
) {

    /**
     * Which postings to keep. Initial market: India-based roles plus remote roles open to India.
     *
     * @param countries     ISO-3166 alpha-2 codes of on-site/hybrid locations to keep
     * @param includeRemote keep remote roles (unless explicitly restricted to another country)
     * @param techRolesOnly drop sales/ops/etc. postings that ATS boards also list
     */
    public record Filters(@DefaultValue("IN") List<String> countries, @DefaultValue("true") boolean includeRemote,
                          @DefaultValue("true") boolean techRolesOnly) {
    }

    public record Board(@DefaultValue("true") boolean enabled, String baseUrl) {
    }

    public record Adzuna(@DefaultValue("true") boolean enabled, @DefaultValue("https://api.adzuna.com") String baseUrl,
                         String appId, String appKey, @DefaultValue("in") String country,
                         @DefaultValue("14") int maxDaysOld, @DefaultValue("2") int pagesPerQuery,
                         @DefaultValue("50") int resultsPerPage, @DefaultValue List<String> queries) {
        public boolean configured() {
            return enabled && appId != null && !appId.isBlank() && appKey != null && !appKey.isBlank();
        }
    }

    public record Sample(@DefaultValue("false") boolean enabled) {
    }
}
