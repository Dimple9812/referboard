package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by referral-service when a follow-up is due. The drafted message is for the user to copy and
 * send themselves; nothing is ever sent to the contact automatically.
 *
 * <p>reminderType: SEND_REFERRAL_MESSAGE (connected 2 days ago), FOLLOW_UP (no reply in 5 days).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReferralReminderDueEvent(
        EventMetadata metadata,
        UUID reminderId,
        UUID userId,
        UUID contactId,
        UUID jobId,
        String contactName,
        String jobTitle,
        String companyName,
        String reminderType,
        String message,
        String draftedFollowUp,
        Instant dueAt
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.REFERRAL_REMINDER_DUE;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
