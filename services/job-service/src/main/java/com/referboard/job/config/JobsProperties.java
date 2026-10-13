package com.referboard.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "referboard.jobs")
public record JobsProperties(
        @DefaultValue("PT60S") Duration searchCacheTtl,
        @DefaultValue("50") int maxPageSize,
        @DefaultValue Match match,
        @DefaultValue Digest digest
) {
    public record Match(@DefaultValue("35") int threshold, @DefaultValue("300") int perUserLimit,
                        @DefaultValue("400") int candidateLimit, @DefaultValue("60000") long refreshIntervalMs) {
    }

    public record Digest(@DefaultValue("0 0 8 * * *") String cron, @DefaultValue("Asia/Kolkata") String zone,
                         @DefaultValue("60") int minScore, @DefaultValue("10") int maxJobs) {
    }
}
