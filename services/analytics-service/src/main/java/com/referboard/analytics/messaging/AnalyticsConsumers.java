package com.referboard.analytics.messaging;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.ProcessedEventStore;
import com.referboard.events.AnalysisCompletedEvent;
import com.referboard.events.ApplicationStatusChangedEvent;
import com.referboard.events.InterviewPrepCreatedEvent;
import com.referboard.events.ProStatusChangedEvent;
import com.referboard.events.ReferralStatusChangedEvent;
import com.referboard.events.ResumeVersionCreatedEvent;
import com.referboard.events.Topics;
import com.referboard.events.UserRegisteredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Projects events into the analytics read model. Every handler is idempotent (processed_events, in the same
 * transaction) and tolerant of order: "first reached" times only ever move earlier, and the current stage or status
 * only changes for an event newer than the one already applied.
 */
@Component
public class AnalyticsConsumers {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** Tracker stage → the column recording when a card first reached it. */
    private static final Map<String, String> STAGE_COLUMN = Map.of("APPLIED", "applied_at",
            "REFERRAL_REQUESTED", "referral_requested_at", "REFERRED", "referred_at", "INTERVIEW", "interview_at",
            "OFFER", "offer_at", "REJECTED", "rejected_at");
    private static final Map<String, String> STAGE_KIND = Map.of("SAVED", "JOB_SAVED", "APPLIED", "APPLIED",
            "INTERVIEW", "INTERVIEW", "OFFER", "OFFER", "REJECTED", "REJECTED");
    /** Referral status → first-reached column and activity kind. */
    private static final Map<String, String> STATUS_COLUMN = Map.of("REQUEST_SENT", "request_sent_at",
            "CONNECTED", "connected_at", "REFERRAL_ASKED", "asked_at", "REFERRED", "referred_at",
            "NO_RESPONSE", "no_response_at");
    private static final Map<String, String> STATUS_KIND = Map.of("REQUEST_SENT", "CONNECTION_REQUESTED",
            "CONNECTED", "CONNECTED", "REFERRAL_ASKED", "REFERRAL_ASKED", "REFERRED", "REFERRED");

    private final EventJson json;
    private final ProcessedEventStore processed;
    private final JdbcTemplate jdbc;

    public AnalyticsConsumers(EventJson json, ProcessedEventStore processed, JdbcTemplate jdbc) {
        this.json = json;
        this.processed = processed;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.USER_REGISTERED, groupId = "analytics-service")
    @Transactional
    public void onUserRegistered(String payload) {
        UserRegisteredEvent e = json.read(payload, UserRegisteredEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.USER_REGISTERED)) {
            return;
        }
        Instant at = e.metadata().occurredAt();
        jdbc.update("""
                insert into user_facts (user_id, registered_at) values (?, ?)
                on conflict (user_id) do update set registered_at = least(coalesce(user_facts.registered_at, excluded.registered_at), excluded.registered_at)
                """, e.userId(), ts(at));
        activity(e.metadata().eventId(), e.userId(), "SIGNUP", at, null, null, null, null);
    }

    @KafkaListener(topics = Topics.APPLICATION_STATUS_CHANGED, groupId = "analytics-service")
    @Transactional
    public void onApplicationStatusChanged(String payload) {
        ApplicationStatusChangedEvent e = json.read(payload, ApplicationStatusChangedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.APPLICATION_STATUS_CHANGED)) {
            return;
        }
        Instant at = e.changedAt() == null ? e.metadata().occurredAt() : e.changedAt();
        jdbc.update("""
                insert into applications (application_id, user_id, job_id, stage, stage_changed_at, created_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (application_id) do update set
                    stage = case when excluded.stage_changed_at >= applications.stage_changed_at then excluded.stage else applications.stage end,
                    stage_changed_at = greatest(applications.stage_changed_at, excluded.stage_changed_at),
                    created_at = least(applications.created_at, excluded.created_at),
                    job_id = coalesce(applications.job_id, excluded.job_id)
                """, e.applicationId(), e.userId(), e.jobId(), e.newStage(), ts(at), ts(at));
        String column = STAGE_COLUMN.get(e.newStage());
        if (column != null) {
            // column comes from a fixed map, never from the event
            jdbc.update("update applications set " + column + " = least(coalesce(" + column + ", ?), ?) where application_id = ?",
                    ts(at), ts(at), e.applicationId());
        }
        String kind = e.previousStage() == null && "SAVED".equals(e.newStage()) ? "JOB_SAVED"
                : "SAVED".equals(e.newStage()) ? null : STAGE_KIND.get(e.newStage());
        if (kind != null) {
            activity(e.metadata().eventId(), e.userId(), kind, at, null, null, null, e.applicationId());
        }
    }

    @KafkaListener(topics = Topics.REFERRAL_STATUS_CHANGED, groupId = "analytics-service")
    @Transactional
    public void onReferralStatusChanged(String payload) {
        ReferralStatusChangedEvent e = json.read(payload, ReferralStatusChangedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.REFERRAL_STATUS_CHANGED)) {
            return;
        }
        Instant at = e.changedAt() == null ? e.metadata().occurredAt() : e.changedAt();
        jdbc.update("""
                insert into contacts (contact_id, user_id, job_id, status, status_changed_at, created_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (contact_id) do update set
                    status = case when excluded.status_changed_at >= contacts.status_changed_at then excluded.status else contacts.status end,
                    status_changed_at = greatest(contacts.status_changed_at, excluded.status_changed_at),
                    created_at = least(contacts.created_at, excluded.created_at)
                """, e.contactId(), e.userId(), e.jobId(), e.newStatus(), ts(at), ts(at));
        String column = STATUS_COLUMN.get(e.newStatus());
        if (column != null) {
            jdbc.update("update contacts set " + column + " = least(coalesce(" + column + ", ?), ?) where contact_id = ?",
                    ts(at), ts(at), e.contactId());
        }
        String kind = e.previousStatus() == null ? "CONTACT_ADDED" : STATUS_KIND.get(e.newStatus());
        if (kind != null) {
            activity(e.metadata().eventId(), e.userId(), kind, at, null, null, null, e.contactId());
        }
    }

    @KafkaListener(topics = Topics.ANALYSIS_COMPLETED, groupId = "analytics-service")
    @Transactional
    public void onAnalysisCompleted(String payload) {
        AnalysisCompletedEvent e = json.read(payload, AnalysisCompletedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.ANALYSIS_COMPLETED)) {
            return;
        }
        activity(e.metadata().eventId(), e.userId(), "ANALYSIS",
                e.completedAt() == null ? e.metadata().occurredAt() : e.completedAt(), e.score(), null, null, e.analysisId());
    }

    @KafkaListener(topics = Topics.RESUME_VERSION_CREATED, groupId = "analytics-service")
    @Transactional
    public void onResumeVersionCreated(String payload) {
        ResumeVersionCreatedEvent e = json.read(payload, ResumeVersionCreatedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.RESUME_VERSION_CREATED)) {
            return;
        }
        activity(e.metadata().eventId(), e.userId(), "TAILORED_RESUME", e.metadata().occurredAt(), e.scoreAfter(),
                e.scoreAfter() - e.scoreBefore(), null, e.resumeVersionId());
    }

    @KafkaListener(topics = Topics.INTERVIEW_PREP_CREATED, groupId = "analytics-service")
    @Transactional
    public void onPrepCreated(String payload) {
        InterviewPrepCreatedEvent e = json.read(payload, InterviewPrepCreatedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.INTERVIEW_PREP_CREATED)) {
            return;
        }
        activity(e.metadata().eventId(), e.userId(), "PREP_PACK",
                e.createdAt() == null ? e.metadata().occurredAt() : e.createdAt(), null, null, null, e.prepId());
    }

    @KafkaListener(topics = Topics.PRO_STATUS_CHANGED, groupId = "analytics-service")
    @Transactional
    public void onProStatusChanged(String payload) {
        ProStatusChangedEvent e = json.read(payload, ProStatusChangedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.PRO_STATUS_CHANGED)) {
            return;
        }
        Instant at = e.changedAt() == null ? e.metadata().occurredAt() : e.changedAt();
        boolean purchase = "PURCHASE".equals(e.reason());
        jdbc.update("""
                insert into user_facts (user_id, pro_until, pro_changed_at, first_purchase_at) values (?, ?, ?, ?)
                on conflict (user_id) do update set
                    pro_until = case when user_facts.pro_changed_at is null or excluded.pro_changed_at >= user_facts.pro_changed_at
                                     then excluded.pro_until else user_facts.pro_until end,
                    pro_changed_at = greatest(user_facts.pro_changed_at, excluded.pro_changed_at),
                    first_purchase_at = least(coalesce(user_facts.first_purchase_at, excluded.first_purchase_at), excluded.first_purchase_at)
                """, e.userId(), ts(e.proUntil()), ts(at), purchase ? ts(at) : null);
        activity(e.metadata().eventId(), e.userId(), purchase ? "PURCHASE" : "REFUND", at, null, null, e.amountPaise(),
                e.orderId());
    }

    // ---------------------------------------------------------------------------------------------------------

    private void activity(UUID eventId, UUID userId, String kind, Instant at, Integer score, Integer delta,
                          Long amountPaise, UUID ref) {
        jdbc.update("""
                insert into activity (event_id, user_id, kind, occurred_at, day, score, delta, amount_paise, ref_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (event_id) do nothing
                """, eventId, userId, kind, ts(at), LocalDate.ofInstant(at, INDIA), score, delta, amountPaise, ref);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
