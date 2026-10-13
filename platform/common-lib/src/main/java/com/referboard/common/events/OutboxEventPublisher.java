package com.referboard.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.events.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes events to the service's own {@code outbox_events} table (see each service's Flyway V1 migration).
 * {@code MANDATORY} propagation makes it a programming error to publish outside a business transaction.
 */
public class OutboxEventPublisher implements EventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxEventPublisher(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + event.metadata().eventType(), e);
        }
        jdbc.update("""
                        insert into outbox_events (id, topic, message_key, event_type, payload, created_at)
                        values (?, ?, ?, ?, cast(? as jsonb), now())
                        """,
                event.metadata().eventId(), event.topic(), event.key(), event.metadata().eventType(), payload);
    }
}
