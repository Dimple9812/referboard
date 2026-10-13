package com.referboard.tracker.service;

import com.referboard.common.web.ApiException;
import com.referboard.tracker.client.JobClient;
import com.referboard.tracker.config.TrackerProperties;
import com.referboard.tracker.domain.Application;
import com.referboard.tracker.domain.ApplicationEvent;
import com.referboard.tracker.domain.Stage;
import com.referboard.tracker.mapper.TrackerMapper;
import com.referboard.tracker.repository.ApplicationEventRepository;
import com.referboard.tracker.repository.ApplicationInserts;
import com.referboard.tracker.repository.ApplicationRepository;
import com.referboard.tracker.web.dto.TrackerDtos.ApplicationDetail;
import com.referboard.tracker.web.dto.TrackerDtos.Board;
import com.referboard.tracker.web.dto.TrackerDtos.CardResponse;
import com.referboard.tracker.web.dto.TrackerDtos.Column;
import com.referboard.tracker.web.dto.TrackerDtos.CreateApplicationRequest;
import com.referboard.tracker.web.dto.TrackerDtos.MoveRequest;
import com.referboard.tracker.web.dto.TrackerDtos.UpdateApplicationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The board API: add, move, edit, annotate and remove application cards. */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final ApplicationRepository applications;
    private final ApplicationEventRepository timeline;
    private final CardService cards;
    private final JobClient jobClient;
    private final TrackerMapper mapper;
    private final TrackerProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, ApplicationEventRepository timeline,
                              CardService cards, JobClient jobClient, TrackerMapper mapper, TrackerProperties props,
                              TransactionTemplate tx, Clock clock) {
        this.applications = applications;
        this.timeline = timeline;
        this.cards = cards;
        this.jobClient = jobClient;
        this.mapper = mapper;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Adds a ReferBoard job (details copied from job-service) or a job found elsewhere (details typed in). */
    public CardResponse create(UUID userId, CreateApplicationRequest req) {
        Stage stage = req.stage() == null ? Stage.SAVED : req.stage();
        ApplicationInserts.NewCard card;
        if (req.jobId() != null) {
            JobClient.Job job = fetchJob(req.jobId());   // outside the transaction
            card = new ApplicationInserts.NewCard(userId, req.jobId(), job.title(), job.companyName(), job.location(),
                    job.applyUrl(), stage, 0, Application.Source.MANUAL, req.appliedAt(), trimToNull(req.notes()));
        } else {
            if (isBlank(req.jobTitle()) || isBlank(req.companyName())) {
                throw ApiException.badRequest("JOB_REQUIRED",
                        "Give either jobId, or jobTitle and companyName for a job found elsewhere.");
            }
            card = new ApplicationInserts.NewCard(userId, null, req.jobTitle().trim(), req.companyName().trim(),
                    trimToNull(req.location()), trimToNull(req.applyUrl()), stage, 0, Application.Source.MANUAL,
                    req.appliedAt(), trimToNull(req.notes()));
        }
        return tx.execute(status -> {
            if (applications.countByUserId(userId) >= props.maxApplications()) {
                throw ApiException.conflict("BOARD_FULL",
                        "You can track up to " + props.maxApplications() + " applications. Remove old ones first.");
            }
            Application created = cards.create(card, "Added to " + stage.label(), clock.instant())
                    .orElseThrow(() -> {
                        UUID existing = applications.findByUserIdAndJobId(userId, req.jobId())
                                .map(Application::getId).orElse(null);
                        return new ApiException(HttpStatus.CONFLICT, "ALREADY_TRACKED",
                                "This job is already on your board (application " + existing + ").");
                    });
            return mapper.toCard(created);
        });
    }

    @Transactional(readOnly = true)
    public Board board(UUID userId) {
        Map<Stage, List<CardResponse>> byStage = new EnumMap<>(Stage.class);
        for (Stage s : Stage.values()) {
            byStage.put(s, new ArrayList<>());
        }
        List<Application> all = applications.findByUserIdOrderByPositionAsc(userId);
        all.forEach(a -> byStage.get(a.getStage()).add(mapper.toCard(a)));
        List<Column> columns = new ArrayList<>();
        for (Stage s : Stage.values()) {
            columns.add(new Column(s, s.label(), byStage.get(s).size(), byStage.get(s)));
        }
        return new Board(columns, all.size());
    }

    @Transactional(readOnly = true)
    public ApplicationDetail detail(UUID userId, UUID id) {
        Application a = find(userId, id);
        return new ApplicationDetail(mapper.toCard(a),
                timeline.findByApplicationIdOrderBySeqAsc(id).stream().map(mapper::toEntry).toList());
    }

    @Transactional(readOnly = true)
    public CardResponse byJob(UUID userId, UUID jobId) {
        return applications.findByUserIdAndJobId(userId, jobId).map(mapper::toCard)
                .orElseThrow(() -> ApiException.notFound("Application for job", jobId));
    }

    /** Drag and drop: to another column (recorded and published) or to another place in the same column. */
    @Transactional
    public CardResponse move(UUID userId, UUID id, MoveRequest req) {
        Application a = find(userId, id);
        List<Application> column = new ArrayList<>(applications.findByUserIdAndStageOrderByPositionAsc(userId,
                req.stage()));
        column.removeIf(c -> c.getId().equals(a.getId()));
        int index = req.index() == null ? 0 : req.index();
        double position = BoardPositions.at(column.stream().map(Application::getPosition).toList(), index);
        if (Double.isNaN(position)) {
            log.debug("Renumbering column {} for user {}", req.stage(), userId);
            for (int i = 0; i < column.size(); i++) {
                column.get(i).setPosition(BoardPositions.renumbered(i));
            }
            applications.flush();
            position = BoardPositions.at(column.stream().map(Application::getPosition).toList(), index);
        }
        if (a.getStage() == req.stage()) {
            a.setPosition(position);
            applications.saveAndFlush(a);
        } else {
            cards.changeStage(a, req.stage(), position, "Moved to " + req.stage().label(), clock.instant());
        }
        return mapper.toCard(a);
    }

    @Transactional
    public CardResponse update(UUID userId, UUID id, UpdateApplicationRequest req) {
        Application a = find(userId, id);
        List<String> changed = new ArrayList<>();
        boolean editsJobFields = req.jobTitle() != null || req.companyName() != null || req.location() != null
                || req.applyUrl() != null;
        if (editsJobFields && a.getJobId() != null) {
            throw ApiException.badRequest("JOB_FIELDS_READ_ONLY",
                    "Title, company, location and link come from the job posting and can't be edited.");
        }
        set(changed, "jobTitle", a.getJobTitle(), trimToNull(req.jobTitle()), a::setJobTitle);
        set(changed, "companyName", a.getCompanyName(), trimToNull(req.companyName()), a::setCompanyName);
        if (req.location() != null) {
            set(changed, "location", a.getLocation(), trimToNull(req.location()), a::setLocation, true);
        }
        if (req.applyUrl() != null) {
            set(changed, "applyUrl", a.getApplyUrl(), trimToNull(req.applyUrl()), a::setApplyUrl, true);
        }
        if (req.notes() != null) {
            set(changed, "notes", a.getNotes(), trimToNull(req.notes()), a::setNotes, true);
        }
        if (req.appliedAt() != null) {
            set(changed, "appliedAt", a.getAppliedAt(), req.appliedAt(), a::setAppliedAt);
        }
        if (Boolean.TRUE.equals(req.clearNextAction())) {
            if (a.getNextAction() != null || a.getNextActionAt() != null) {
                changed.add("nextAction");
            }
            a.setNextAction(null);
            a.setNextActionAt(null);
        } else {
            if (req.nextAction() != null) {
                set(changed, "nextAction", a.getNextAction(), trimToNull(req.nextAction()), a::setNextAction, true);
            }
            if (req.nextActionAt() != null) {
                set(changed, "nextActionAt", a.getNextActionAt(), req.nextActionAt(), a::setNextActionAt);
            }
        }
        if (!changed.isEmpty()) {
            applications.saveAndFlush(a);
            timeline.save(new ApplicationEvent(a.getId(), ApplicationEvent.Type.UPDATED, null, null,
                    "Updated " + String.join(", ", changed), Map.of("fields", changed), clock.instant()));
        }
        return mapper.toCard(a);
    }

    @Transactional
    public ApplicationDetail addNote(UUID userId, UUID id, String text) {
        Application a = find(userId, id);
        timeline.save(ApplicationEvent.of(a.getId(), ApplicationEvent.Type.NOTE, text.trim(), clock.instant()));
        return new ApplicationDetail(mapper.toCard(a),
                timeline.findByApplicationIdOrderBySeqAsc(id).stream().map(mapper::toEntry).toList());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        Application a = find(userId, id);
        a.setDeletedAt(clock.instant());
        applications.saveAndFlush(a);
    }

    // ---------------------------------------------------------------------------------------------------------

    private Application find(UUID userId, UUID id) {
        return applications.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Application", id));
    }

    private JobClient.Job fetchJob(UUID jobId) {
        try {
            JobClient.JobDetail d = jobClient.job(jobId);
            if (d == null || d.job() == null) {
                throw ApiException.notFound("Job", jobId);
            }
            return d.job();
        } catch (feign.FeignException e) {
            log.warn("job-service call failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
                    "A dependent service is unavailable. Please try again shortly.");
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                throw ApiException.notFound("Job", jobId);
            }
            throw e;
        }
    }

    private static <T> void set(List<String> changed, String name, T current, T value,
                                java.util.function.Consumer<T> setter) {
        set(changed, name, current, value, setter, value != null);
    }

    private static <T> void set(List<String> changed, String name, T current, T value,
                                java.util.function.Consumer<T> setter, boolean apply) {
        if (apply && !Objects.equals(current, value)) {
            setter.accept(value);
            changed.add(name);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
