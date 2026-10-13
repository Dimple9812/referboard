package com.referboard.events;

/**
 * Marker for all event payloads. Every event exposes its metadata, its Kafka topic and its message key.
 */
public interface DomainEvent {

    EventMetadata metadata();

    /** Topic this event is published to. */
    String topic();

    /** Partition key; guarantees ordering per entity. */
    String key();
}
