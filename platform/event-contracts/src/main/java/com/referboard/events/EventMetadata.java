package com.referboard.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Header block carried by every event.
 *
 * @param eventId       unique per event; consumers use it for idempotency (processed_events table)
 * @param eventType     logical name, e.g. {@code ProfileUpdated}
 * @param schemaVersion payload schema version (additive changes keep the version, breaking changes bump the topic)
 * @param occurredAt    when the fact happened in the producing service
 * @param producer      producing service name
 * @param correlationId request correlation id that caused the event, for log tracing across services
 */
public record EventMetadata(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        String correlationId
) {
    public static EventMetadata of(String eventType, String producer, String correlationId) {
        return new EventMetadata(UUID.randomUUID(), eventType, 1, Instant.now(), producer, correlationId);
    }
}
