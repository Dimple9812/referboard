package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Published by profile-service whenever the parsed profile or job preferences change.
 * Carries everything job-service needs to score matches, so job-service never reads profile tables.
 *
 * @param profileVersion monotonically increasing; consumers ignore events older than what they hold
 * @param embedding      profile embedding (dimension = {@code embeddingDimensions}); null if embedding failed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileUpdatedEvent(
        EventMetadata metadata,
        UUID userId,
        long profileVersion,
        String headline,
        List<String> skills,
        BigDecimal totalExperienceYears,
        String college,
        List<String> pastCompanies,
        Preferences preferences,
        float[] embedding,
        String embeddingModel,
        int embeddingDimensions
) implements DomainEvent {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Preferences(
            List<String> roles,
            List<String> locations,
            boolean remoteOnly,
            boolean openToRemote,
            Long minSalaryInr,
            String experienceLevel
    ) {
    }

    @Override
    public String topic() {
        return Topics.PROFILE_UPDATED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
