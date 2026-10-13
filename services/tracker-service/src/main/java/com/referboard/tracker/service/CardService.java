package com.referboard.tracker.service;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.events.ApplicationStatusChangedEvent;
import com.referboard.tracker.domain.Application;
import com.referboard.tracker.domain.ApplicationEvent;
import com.referboard.tracker.domain.Stage;
import com.referboard.tracker.repository.ApplicationEventRepository;
import com.referboard.tracker.repository.ApplicationInserts;
import com.referboard.tracker.repository.ApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Card creation and stage changes, shared by the HTTP API and the Kafka consumers so both record the same history
 * and publish the same ApplicationStatusChanged events. Callers own the transaction.
 */
@Service
public class CardService {

    private final ApplicationRepository applications;
    private final ApplicationEventRepository timeline;
    private final ApplicationInserts inserts;
    private final EventPublisher events;
    private final EventJson eventJson;

    public CardService(ApplicationRepository applications, ApplicationEventRepository timeline,
                       ApplicationInserts inserts, EventPublisher events, EventJson eventJson) {
        this.applications = applications;
        this.timeline = timeline;
        this.inserts = inserts;
        this.events = events;
        this.eventJson = eventJson;
    }

    /**
     * Creates a card at the top of its column, picking up a tailored resume or a closed posting that arrived
     * before the card existed.
     *
     * @return the new card, or empty if the user already tracks this job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Application> create(ApplicationInserts.NewCard card, String createdMessage, Instant now) {
        Double top = applications.minPosition(card.userId(), card.stage());
        double position = top == null ? 0 : top - BoardPositions.STEP;
        ApplicationInserts.NewCard positioned = new ApplicationInserts.NewCard(card.userId(), card.jobId(),
                card.jobTitle(), card.companyName(), card.location(), card.applyUrl(), card.stage(), position,
                card.source(), card.appliedAt() != null ? card.appliedAt()
                : card.stage() == Stage.APPLIED ? now : null, card.notes());
        Optional<UUID> id = inserts.insert(positioned, now);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Application app = applications.findById(id.get()).orElseThrow();
        timeline.save(new ApplicationEvent(app.getId(), ApplicationEvent.Type.CREATED, null, app.getStage(),
                createdMessage, Map.of(), now));
        if (app.getJobId() != null) {
            inserts.takePendingResume(app.getUserId(), app.getJobId())
                    .ifPresent(p -> linkResume(app, p.resumeVersionId(), p.versionName(), Map.of(), now));
            inserts.closedAt(app.getJobId()).ifPresent(closedAt -> markJobClosed(app, closedAt, now));
        }
        publish(app, null, now);
        return Optional.of(app);
    }

    /** Moves a card to another column (top of it unless {@code position} is given), with history and an event. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeStage(Application app, Stage target, Double position, String message, Instant now) {
        Stage from = app.getStage();
        if (position == null) {
            Double top = applications.minPosition(app.getUserId(), target);
            position = top == null ? 0 : top - BoardPositions.STEP;
        }
        app.moveTo(target, now);
        app.setPosition(position);
        applications.saveAndFlush(app);   // optimistic-lock check before side effects
        timeline.save(new ApplicationEvent(app.getId(), ApplicationEvent.Type.STAGE_CHANGED, from, target,
                message, Map.of(), now));
        publish(app, from, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void linkResume(Application app, UUID resumeVersionId, String versionName, Map<String, Object> details,
                           Instant now) {
        app.setResumeVersionId(resumeVersionId);
        String name = versionName == null || versionName.isBlank() ? "Tailored resume" : "“" + versionName + "”";
        timeline.save(new ApplicationEvent(app.getId(), ApplicationEvent.Type.RESUME_LINKED, null, null,
                name + " linked to this application", details, now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markJobClosed(Application app, Instant closedAt, Instant now) {
        if (app.getJobClosedAt() != null) {
            return;
        }
        app.setJobClosedAt(closedAt);
        timeline.save(ApplicationEvent.of(app.getId(), ApplicationEvent.Type.JOB_CLOSED,
                "The job posting was closed", now));
    }

    /** Re-spaces a column whose positions have run out of room; returns the card ids in order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Application> renumber(UUID userId, Stage stage) {
        List<Application> column = applications.findByUserIdAndStageOrderByPositionAsc(userId, stage);
        for (int i = 0; i < column.size(); i++) {
            column.get(i).setPosition(BoardPositions.renumbered(i));
        }
        applications.flush();
        return column;
    }

    private void publish(Application app, Stage from, Instant now) {
        events.publish(new ApplicationStatusChangedEvent(eventJson.newMetadata("ApplicationStatusChanged"),
                app.getUserId(), app.getId(), app.getJobId(), from == null ? null : from.name(),
                app.getStage().name(), app.getResumeVersionId(), now));
    }
}
