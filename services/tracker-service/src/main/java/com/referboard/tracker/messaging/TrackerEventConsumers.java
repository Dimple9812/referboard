package com.referboard.tracker.messaging;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.ProcessedEventStore;
import com.referboard.events.JobClosedEvent;
import com.referboard.events.ReferralStatusChangedEvent;
import com.referboard.events.ResumeVersionCreatedEvent;
import com.referboard.events.Topics;
import com.referboard.tracker.domain.Application;
import com.referboard.tracker.domain.ApplicationEvent;
import com.referboard.tracker.domain.Stage;
import com.referboard.tracker.repository.ApplicationEventRepository;
import com.referboard.tracker.repository.ApplicationInserts;
import com.referboard.tracker.repository.ApplicationRepository;
import com.referboard.tracker.service.CardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps the board in step with the rest of ReferBoard. Each handler runs in one transaction that first records the
 * event id, so redeliveries are no-ops; failures are retried, then parked on {@code <topic>.DLT}.
 *
 * <ul>
 *   <li>Referral progress: once the user reaches out about a job, the job appears on the board; asking for a
 *       referral moves it to "Referral requested", being referred to "Referred" (only ever forward from
 *       Saved/Applied/Referral requested). Every update is added to the card's timeline.</li>
 *   <li>Tailored resumes are linked to the job's card, or held until the card exists.</li>
 *   <li>Closed postings are flagged on every card for that job.</li>
 * </ul>
 */
@Component
public class TrackerEventConsumers {

    private static final Logger log = LoggerFactory.getLogger(TrackerEventConsumers.class);

    /** Referral statuses that mean the user is actively pursuing this job. */
    private static final Set<String> ENGAGED = Set.of("REQUEST_SENT", "CONNECTED", "REFERRAL_ASKED", "REFERRED");

    private final EventJson json;
    private final ProcessedEventStore processed;
    private final ApplicationRepository applications;
    private final ApplicationEventRepository timeline;
    private final ApplicationInserts inserts;
    private final CardService cards;
    private final Clock clock;

    public TrackerEventConsumers(EventJson json, ProcessedEventStore processed, ApplicationRepository applications,
                                 ApplicationEventRepository timeline, ApplicationInserts inserts, CardService cards,
                                 Clock clock) {
        this.json = json;
        this.processed = processed;
        this.applications = applications;
        this.timeline = timeline;
        this.inserts = inserts;
        this.cards = cards;
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.REFERRAL_STATUS_CHANGED, groupId = "tracker-service")
    @Transactional
    public void onReferralStatusChanged(String payload) {
        ReferralStatusChangedEvent e = json.read(payload, ReferralStatusChangedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.REFERRAL_STATUS_CHANGED)) {
            return;
        }
        Instant now = clock.instant();
        Optional<Stage> target = targetStage(e.newStatus());
        Optional<Application> existing = applications.findByUserIdAndJobId(e.userId(), e.jobId());
        Application app;
        if (existing.isPresent()) {
            app = existing.get();
        } else {
            if (!ENGAGED.contains(e.newStatus())) {
                return;   // nothing on the board and nothing worth adding
            }
            if (e.jobTitle() == null || e.companyName() == null) {
                log.warn("ReferralStatusChanged {} has no job details (old producer?); not creating a card",
                        e.metadata().eventId());
                return;
            }
            Stage initial = target.orElse(Stage.SAVED);
            var created = cards.create(new ApplicationInserts.NewCard(e.userId(), e.jobId(), e.jobTitle(),
                    e.companyName(), e.jobLocation(), e.applyUrl(), initial, 0, Application.Source.REFERRAL, null,
                    null), "Added from your referral outreach", now);
            if (created.isEmpty()) {
                // Raced with the user adding it: the card exists now.
                app = applications.findByUserIdAndJobId(e.userId(), e.jobId()).orElseThrow();
            } else {
                app = created.get();
            }
        }
        String summary = summary(e.newStatus(), e.contactName());
        if (summary != null) {
            app.setReferralSummary(truncate(summary, 300));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("contactId", e.contactId().toString());
            details.put("contactName", e.contactName());
            details.put("referralStatus", e.newStatus());
            timeline.save(new ApplicationEvent(app.getId(), ApplicationEvent.Type.REFERRAL_UPDATE, null, null,
                    summary, details, now));
        }
        if (target.isPresent() && app.getStage().canAutoAdvanceTo(target.get())) {
            cards.changeStage(app, target.get(), null, summary, now);
        }
    }

    @KafkaListener(topics = Topics.RESUME_VERSION_CREATED, groupId = "tracker-service")
    @Transactional
    public void onResumeVersionCreated(String payload) {
        ResumeVersionCreatedEvent e = json.read(payload, ResumeVersionCreatedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.RESUME_VERSION_CREATED)) {
            return;
        }
        if (e.jobId() == null) {
            return;   // tailored to a pasted JD, not a ReferBoard job: nothing to link
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resumeVersionId", e.resumeVersionId().toString());
        details.put("scoreBefore", e.scoreBefore());
        details.put("scoreAfter", e.scoreAfter());
        details.put("fileFormat", e.fileFormat());
        applications.findByUserIdAndJobId(e.userId(), e.jobId()).ifPresentOrElse(
                app -> cards.linkResume(app, e.resumeVersionId(), e.versionName(), details, clock.instant()),
                () -> inserts.savePendingResume(e.userId(), e.jobId(), e.resumeVersionId(), e.versionName()));
    }

    @KafkaListener(topics = Topics.JOB_CLOSED, groupId = "tracker-service")
    @Transactional
    public void onJobClosed(String payload) {
        JobClosedEvent e = json.read(payload, JobClosedEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.JOB_CLOSED)) {
            return;
        }
        Instant closedAt = e.closedAt() == null ? clock.instant() : e.closedAt();
        inserts.recordClosedJob(e.jobId(), closedAt);
        for (Application app : applications.findByJobIdAndJobClosedAtIsNull(e.jobId())) {
            cards.markJobClosed(app, closedAt, clock.instant());
        }
    }

    static Optional<Stage> targetStage(String referralStatus) {
        return switch (referralStatus) {
            case "REFERRAL_ASKED" -> Optional.of(Stage.REFERRAL_REQUESTED);
            case "REFERRED" -> Optional.of(Stage.REFERRED);
            default -> Optional.empty();
        };
    }

    static String summary(String referralStatus, String contactName) {
        String who = contactName == null || contactName.isBlank() ? "a contact" : contactName;
        return switch (referralStatus) {
            case "REQUEST_SENT" -> "Connection request sent to " + who;
            case "CONNECTED" -> "Connected with " + who;
            case "REFERRAL_ASKED" -> "Asked " + who + " for a referral";
            case "REFERRED" -> "Referred by " + who;
            case "NO_RESPONSE" -> "No response from " + who;
            case "NOT_CONTACTED" -> who + " moved back to not contacted";
            default -> null;
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
