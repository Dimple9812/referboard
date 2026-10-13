package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** Published by ingestion-service when a posting has not been seen for 7 days (or the source reports it closed). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobClosedEvent(
        EventMetadata metadata,
        UUID jobId,
        Instant closedAt,
        String reason
) implements DomainEvent {

    public static final String REASON_UNSEEN_7_DAYS = "UNSEEN_7_DAYS";

    @Override
    public String topic() {
        return Topics.JOB_CLOSED;
    }

    @Override
    public String key() {
        return jobId.toString();
    }
}
