package com.referboard.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.web.CorrelationId;
import com.referboard.events.DomainEvent;
import com.referboard.events.EventMetadata;
import org.slf4j.MDC;

/**
 * Kafka messages travel as JSON strings; each topic carries one event type, so consumers read the payload
 * straight into the concrete record. Also restores the producer's correlation id into the MDC so consumer
 * logs line up with the originating HTTP request.
 */
public class EventJson {

    private final ObjectMapper mapper;
    private final String producerName;

    public EventJson(ObjectMapper mapper, String producerName) {
        this.mapper = mapper;
        this.producerName = producerName;
    }

    public <T extends DomainEvent> T read(String json, Class<T> type) {
        try {
            T event = mapper.readValue(json, type);
            if (event.metadata() != null && event.metadata().correlationId() != null) {
                MDC.put(CorrelationId.MDC_KEY, event.metadata().correlationId());
            }
            return event;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unreadable " + type.getSimpleName() + " payload", e);
        }
    }

    /** Metadata for a new event produced by this service, tagged with the current request's correlation id. */
    public EventMetadata newMetadata(String eventType) {
        return EventMetadata.of(eventType, producerName, CorrelationId.current());
    }
}
