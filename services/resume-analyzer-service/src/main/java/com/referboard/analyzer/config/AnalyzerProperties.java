package com.referboard.analyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "referboard.analyzer")
public record AnalyzerProperties(
        @DefaultValue("5") int freeAnalysesPerMonth,
        @DefaultValue("200") int minJdChars,
        @DefaultValue("30000") int maxJdChars,
        @DefaultValue("2097152") long maxUploadBytes
) {
}
