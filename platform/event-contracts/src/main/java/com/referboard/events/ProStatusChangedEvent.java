package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's Pro pass changed: bought (PURCHASE) or refunded (REFUND). {@code proUntil} is the new end of Pro access
 * (null or in the past means Free); consumers apply the latest one by {@code changedAt}. Amounts are in paise.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProStatusChangedEvent(
        EventMetadata metadata,
        UUID userId,
        String reason,
        String plan,
        Instant proUntil,
        UUID orderId,
        long amountPaise,
        String currency,
        Instant changedAt
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.PRO_STATUS_CHANGED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
