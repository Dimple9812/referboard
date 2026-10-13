package com.referboard.referral.service;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.web.ApiException;
import com.referboard.events.ReferralReminderDueEvent;
import com.referboard.referral.config.ReferralProperties;
import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.ContactStatus;
import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.MessageDraft;
import com.referboard.referral.domain.Reminder;
import com.referboard.referral.domain.UserSnapshot;
import com.referboard.referral.drafting.DraftFacts;
import com.referboard.referral.drafting.TemplateDrafter;
import com.referboard.referral.repository.ContactRepository;
import com.referboard.referral.repository.JobSnapshotRepository;
import com.referboard.referral.repository.MessageDraftRepository;
import com.referboard.referral.repository.ReminderRepository;
import com.referboard.referral.web.dto.ReferralDtos.ReminderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Follow-up reminders: "Connected 2 days ago — send your referral message" and "No reply in 5 days — follow up".
 * A reminder only fires if the contact is still in the status that scheduled it. Firing publishes
 * ReferralReminderDue (notification-service emails it) with a template follow-up the user copies and sends.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    static final int BATCH = 100;

    private final ReminderRepository reminders;
    private final ContactRepository contacts;
    private final JobSnapshotRepository jobs;
    private final MessageDraftRepository drafts;
    private final SnapshotService snapshots;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final ReferralProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReminderService(ReminderRepository reminders, ContactRepository contacts, JobSnapshotRepository jobs,
                           MessageDraftRepository drafts, SnapshotService snapshots, EventPublisher events,
                           EventJson eventJson, ReferralProperties props, TransactionTemplate tx, Clock clock) {
        this.reminders = reminders;
        this.contacts = contacts;
        this.jobs = jobs;
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.events = events;
        this.eventJson = eventJson;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Called inside the status-change transaction. */
    void scheduleFor(Contact c, Instant now) {
        switch (c.getStatus()) {
            case CONNECTED -> reminders.save(new Reminder(c.getUserId(), c.getId(),
                    Reminder.Type.SEND_REFERRAL_MESSAGE, ContactStatus.CONNECTED, now.plus(props.sendReferralAfter())));
            case REFERRAL_ASKED -> reminders.save(new Reminder(c.getUserId(), c.getId(), Reminder.Type.FOLLOW_UP,
                    ContactStatus.REFERRAL_ASKED, now.plus(props.followUpAfter())));
            default -> { }
        }
    }

    @Scheduled(fixedDelayString = "${referboard.referral.reminder-scan-interval-ms:300000}", initialDelay = 30_000)
    public void scheduledScan() {
        try {
            int fired = 0;
            int n;
            do {
                n = scanOnce();
                fired += n;
            } while (n == BATCH);
            if (fired > 0) {
                log.info("Processed {} due referral reminders", fired);
            }
        } catch (RuntimeException e) {
            log.warn("Reminder scan failed; will retry next run", e);
        }
    }

    /** Processes one batch of due reminders; rows are locked with SKIP LOCKED so instances don't double-fire. */
    public int scanOnce() {
        Integer n = tx.execute(status -> {
            Instant now = clock.instant();
            List<Reminder> due = reminders.lockDue(now, BATCH);
            for (Reminder r : due) {
                process(r, now);
            }
            return due.size();
        });
        return n == null ? 0 : n;
    }

    private void process(Reminder r, Instant now) {
        Optional<Contact> contact = contacts.findById(r.getContactId());   // soft-deleted contacts are filtered out
        if (contact.isEmpty() || contact.get().getStatus() != r.getTriggerStatus()) {
            r.setStatus(Reminder.Status.OBSOLETE);
            return;
        }
        Contact c = contact.get();
        JobSnapshot job = snapshots.storedJob(c.getJobId());
        UserSnapshot user = snapshots.storedUser(c.getUserId());
        DraftFacts facts = DraftFacts.of(c, job, user, null);
        MessageDraft.Kind kind = r.getType() == Reminder.Type.SEND_REFERRAL_MESSAGE
                ? MessageDraft.Kind.REFERRAL_REQUEST : MessageDraft.Kind.FOLLOW_UP;
        String draft = kind == MessageDraft.Kind.REFERRAL_REQUEST
                ? TemplateDrafter.referralRequest(facts, MessageDraft.Tone.WARM)
                : TemplateDrafter.followUp(facts, MessageDraft.Tone.WARM);
        r.setMessage(message(r.getType(), c, job, now));
        r.setDraftedFollowUp(draft);
        r.setStatus(Reminder.Status.FIRED);
        r.setFiredAt(now);
        drafts.save(new MessageDraft(c.getUserId(), c.getId(), kind, MessageDraft.Tone.WARM, draft,
                TemplateDrafter.MODEL, TemplateDrafter.VERSION));
        events.publish(new ReferralReminderDueEvent(eventJson.newMetadata("ReferralReminderDue"), r.getId(),
                c.getUserId(), c.getId(), c.getJobId(), c.getFullName(), job.getTitle(), job.getCompanyName(),
                r.getType().name(), r.getMessage(), draft, r.getDueAt()));
    }

    static String message(Reminder.Type type, Contact c, JobSnapshot job, Instant now) {
        String role = job.getTitle() + " at " + job.getCompanyName();
        if (type == Reminder.Type.SEND_REFERRAL_MESSAGE) {
            return "Connected with " + c.getFullName() + " " + ago(c.getConnectedAt(), now)
                    + " — send your referral message for " + role + ".";
        }
        return "No reply from " + c.getFullName() + " in " + days(c.getReferralAskedAt(), now)
                + " — follow up about " + role + ".";
    }

    private static String ago(Instant since, Instant now) {
        long d = Duration.between(Objects.requireNonNullElse(since, now), now).toDays();
        return d <= 0 ? "today" : d == 1 ? "1 day ago" : d + " days ago";
    }

    private static String days(Instant since, Instant now) {
        long d = Math.max(1, Duration.between(Objects.requireNonNullElse(since, now), now).toDays());
        return d == 1 ? "1 day" : d + " days";
    }

    // ---------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReminderResponse> list(UUID userId, Collection<Reminder.Status> statuses, int limit) {
        List<Reminder> list = reminders.findByUserIdAndStatusInOrderByDueAtDesc(userId,
                statuses == null || statuses.isEmpty() ? Set.of(Reminder.Status.FIRED) : statuses,
                PageRequest.of(0, limit));
        Map<UUID, Contact> contactById = contacts.findAllById(list.stream().map(Reminder::getContactId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Contact::getId, Function.identity()));
        Map<UUID, JobSnapshot> jobById = jobs.findAllById(contactById.values().stream().map(Contact::getJobId)
                .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(JobSnapshot::getJobId, Function.identity()));
        return list.stream()
                .filter(r -> contactById.containsKey(r.getContactId()))
                .map(r -> {
                    Contact c = contactById.get(r.getContactId());
                    return toResponse(r, c, jobById.get(c.getJobId()));
                }).toList();
    }

    List<ReminderResponse> forContact(UUID userId, Contact c, JobSnapshot job) {
        return reminders.findByContactIdAndUserIdOrderByDueAtDesc(c.getId(), userId).stream()
                .map(r -> toResponse(r, c, job)).toList();
    }

    @Transactional
    public ReminderResponse update(UUID userId, UUID reminderId, Reminder.Status target) {
        if (target != Reminder.Status.DONE && target != Reminder.Status.DISMISSED) {
            throw ApiException.badRequest("INVALID_STATUS", "A reminder can only be marked DONE or DISMISSED.");
        }
        Reminder r = reminders.findByIdAndUserId(reminderId, userId)
                .orElseThrow(() -> ApiException.notFound("Reminder", reminderId));
        if (r.getStatus() != Reminder.Status.PENDING && r.getStatus() != Reminder.Status.FIRED
                && r.getStatus() != target) {
            throw new ApiException(HttpStatus.CONFLICT, "REMINDER_CLOSED", "This reminder is already " + r.getStatus());
        }
        r.setStatus(target);
        Contact c = contacts.findById(r.getContactId()).orElse(null);
        JobSnapshot job = c == null ? null : jobs.findById(c.getJobId()).orElse(null);
        return toResponse(r, c, job);
    }

    private static ReminderResponse toResponse(Reminder r, Contact c, JobSnapshot job) {
        return new ReminderResponse(r.getId(), r.getContactId(), c == null ? null : c.getFullName(),
                c == null ? null : c.getJobId(), job == null ? null : job.getTitle(),
                job == null ? null : job.getCompanyName(), r.getType(), r.getStatus(), r.getDueAt(), r.getFiredAt(),
                r.getMessage(), r.getDraftedFollowUp());
    }
}
