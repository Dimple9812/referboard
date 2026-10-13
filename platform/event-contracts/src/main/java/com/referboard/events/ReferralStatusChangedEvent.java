package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by referral-service when a contact moves through the referral pipeline.
 * tracker-service uses it to move the job's application card (e.g. REFERRAL_ASKED → "Referral requested",
 * REFERRED → "Referred").
 *
 * <p>Statuses: NOT_CONTACTED, REQUEST_SENT, CONNECTED, REFERRAL_ASKED, REFERRED, NO_RESPONSE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReferralStatusChangedEvent(
        EventMetadata metadata,
        UUID userId,
        UUID jobId,
        UUID contactId,
        String contactName,
        String previousStatus,
        String newStatus,
        Instant changedAt
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.REFERRAL_STATUS_CHANGED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
