package com.referboard.auth.messaging;

import com.referboard.auth.repository.UserRepository;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.ProcessedEventStore;
import com.referboard.events.ProStatusChangedEvent;
import com.referboard.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps each user's Pro pass in step with billing-service. The next access token (at most one token lifetime later,
 * or straight away when the app refreshes after checkout) carries the PRO role until the pass ends.
 */
@Component
public class ProStatusConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProStatusConsumer.class);

    private final EventJson json;
    private final ProcessedEventStore processed;
    private final UserRepository users;

    public ProStatusConsumer(EventJson json, ProcessedEventStore processed, UserRepository users) {
        this.json = json;
        this.processed = processed;
        this.users = users;
    }

    @KafkaListener(topics = Topics.PRO_STATUS_CHANGED, groupId = "auth-service")
    @Transactional
    public void onProStatusChanged(String payload) {
        ProStatusChangedEvent e = json.read(payload, ProStatusChangedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.PRO_STATUS_CHANGED)) {
            return;
        }
        users.findById(e.userId()).ifPresentOrElse(user -> {
            if (user.applyProChange(e.proUntil(), e.changedAt())) {
                log.info("Pro for user {} now until {} ({})", e.userId(), e.proUntil(), e.reason());
            }
        }, () -> log.warn("Pro status for unknown user {}", e.userId()));
    }
}
