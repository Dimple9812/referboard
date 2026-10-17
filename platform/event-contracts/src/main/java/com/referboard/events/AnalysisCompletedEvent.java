package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** A new (not reused) JD analysis finished. No resume or JD text: just what analytics needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisCompletedEvent(
        EventMetadata metadata,
        UUID userId,
        UUID analysisId,
        UUID jobId,
        String jdSource,
        int score,
        String verdict,
        Instant completedAt
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.ANALYSIS_COMPLETED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
