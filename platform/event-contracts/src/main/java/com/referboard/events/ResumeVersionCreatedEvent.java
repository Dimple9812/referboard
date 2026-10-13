package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Published by resume-analyzer-service when the user generates a tailored resume.
 * tracker-service links it to the application for that job so analytics can compare response rates
 * by resume version.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeVersionCreatedEvent(
        EventMetadata metadata,
        UUID userId,
        UUID resumeVersionId,
        String versionName,
        UUID jobId,
        UUID analysisId,
        int scoreBefore,
        int scoreAfter,
        String fileFormat
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.RESUME_VERSION_CREATED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
