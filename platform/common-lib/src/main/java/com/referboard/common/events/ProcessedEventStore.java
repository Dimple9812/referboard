package com.referboard.common.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotent-consumer guard backed by the service's {@code processed_events} table. Call
 * {@link #markProcessed} at the start of the consumer's transaction: it returns {@code false} for a duplicate
 * delivery, and if processing later fails the marker rolls back with everything else, so the redelivery is
 * processed normally.
 */
public class ProcessedEventStore {

    private final JdbcTemplate jdbc;

    public ProcessedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String topic) {
        int inserted = jdbc.update("""
                insert into processed_events (event_id, topic, processed_at) values (?, ?, now())
                on conflict (event_id) do nothing
                """, eventId, topic);
        return inserted == 1;
    }
}
