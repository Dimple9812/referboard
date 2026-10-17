package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewPrepCreatedEvent(
        EventMetadata metadata,
        UUID userId,
        UUID prepId,
        UUID jobId,
        int questionCount,
        Instant createdAt
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.INTERVIEW_PREP_CREATED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
