package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by tracker-service when an application card changes column.
 * Stages: SAVED, APPLIED, REFERRAL_REQUESTED, REFERRED, INTERVIEW, OFFER, REJECTED.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationStatusChangedEvent(
        EventMetadata metadata,
        UUID userId,
        UUID applicationId,
        UUID jobId,
        String previousStage,
        String newStage,
        UUID resumeVersionId,
        Instant changedAt
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.APPLICATION_STATUS_CHANGED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
