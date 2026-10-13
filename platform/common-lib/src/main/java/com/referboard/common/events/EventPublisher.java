package com.referboard.common.events;

import com.referboard.events.DomainEvent;

/**
 * Publishes domain events. The default implementation is a transactional outbox: call it inside the same
 * {@code @Transactional} method that changes state, and the event is committed atomically with that change,
 * then relayed to Kafka asynchronously. No event is lost if Kafka is down, and none is sent for a rolled-back
 * change.
 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
