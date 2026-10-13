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
 *
 * <p>The job fields (added in a backward-compatible way) let consumers create a card for the job without calling
 * job-service, which needs a user token they don't have.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReferralStatusChangedEvent(
        EventMetadata metadata,
        UUID userId,
        UUID jobId,
        String jobTitle,
        String companyName,
        String jobLocation,
        String applyUrl,
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
