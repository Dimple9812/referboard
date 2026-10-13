package com.referboard.job.service;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.ProcessedEventStore;
import com.referboard.events.JobClosedEvent;
import com.referboard.events.JobUpsertedEvent;
import com.referboard.events.ProfileUpdatedEvent;
import com.referboard.events.Topics;
import com.referboard.job.store.JobStore;
import com.referboard.job.store.MatchProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka consumers. Each runs in one DB transaction that starts by recording the event id, so a redelivered event is
 * a no-op and a failed one rolls back completely (then retried with backoff, finally parked on the DLT).
 */
@Component
public class EventConsumers {

    private static final Logger log = LoggerFactory.getLogger(EventConsumers.class);

    private final EventJson json;
    private final ProcessedEventStore processed;
    private final JobStore jobs;
    private final MatchProfileStore profiles;
    private final MatchingService matching;

    public EventConsumers(EventJson json, ProcessedEventStore processed, JobStore jobs, MatchProfileStore profiles,
                          MatchingService matching) {
        this.json = json;
        this.processed = processed;
        this.jobs = jobs;
        this.profiles = profiles;
        this.matching = matching;
    }

    @KafkaListener(topics = Topics.JOB_UPSERTED, groupId = "job-service")
    @Transactional
    public void onJobUpserted(String payload) {
        JobUpsertedEvent event = json.read(payload, JobUpsertedEvent.class);
        if (!processed.markProcessed(event.metadata().eventId(), Topics.JOB_UPSERTED)) {
            return;
        }
        jobs.upsert(event);
    }

    @KafkaListener(topics = Topics.JOB_CLOSED, groupId = "job-service")
    @Transactional
    public void onJobClosed(String payload) {
        JobClosedEvent event = json.read(payload, JobClosedEvent.class);
        if (!processed.markProcessed(event.metadata().eventId(), Topics.JOB_CLOSED)) {
            return;
        }
        if (!jobs.close(event.jobId(), event.closedAt())) {
            log.debug("JobClosed for unknown or already-closed job {}", event.jobId());
        }
    }

    @KafkaListener(topics = Topics.PROFILE_UPDATED, groupId = "job-service")
    @Transactional
    public void onProfileUpdated(String payload) {
        ProfileUpdatedEvent event = json.read(payload, ProfileUpdatedEvent.class);
        if (!processed.markProcessed(event.metadata().eventId(), Topics.PROFILE_UPDATED)) {
            return;
        }
        if (!profiles.upsert(event)) {
            log.debug("Ignoring stale ProfileUpdated v{} for user {}", event.profileVersion(), event.userId());
            return;
        }
        int stored = matching.recomputeForUser(event.userId());
        log.info("Profile v{} for user {} applied; {} matches stored", event.profileVersion(), event.userId(), stored);
    }
}
