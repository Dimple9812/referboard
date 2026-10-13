package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Published by job-service once a day per user with their top new matches (max 10). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailyMatchDigestEvent(
        EventMetadata metadata,
        UUID userId,
        LocalDate digestDate,
        List<MatchSummary> matches
) implements DomainEvent {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchSummary(
            UUID jobId,
            String title,
            String companyName,
            String location,
            boolean remote,
            int matchPercent,
            List<String> matchedSkills
    ) {
    }

    @Override
    public String topic() {
        return Topics.DAILY_MATCH_DIGEST;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
