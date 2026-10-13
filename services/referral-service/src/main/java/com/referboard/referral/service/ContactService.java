package com.referboard.referral.service;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.web.ApiException;
import com.referboard.events.ReferralStatusChangedEvent;
import com.referboard.referral.config.ReferralProperties;
import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.ContactStatus;
import com.referboard.referral.domain.ContactStatusHistory;
import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.Reminder;
import com.referboard.referral.mapper.ReferralMapper;
import com.referboard.referral.repository.ContactRepository;
import com.referboard.referral.repository.ContactStatusHistoryRepository;
import com.referboard.referral.repository.JobSnapshotRepository;
import com.referboard.referral.repository.MessageDraftRepository;
import com.referboard.referral.repository.ReminderRepository;
import com.referboard.referral.web.dto.ReferralDtos.ContactDetailResponse;
import com.referboard.referral.web.dto.ReferralDtos.ContactPage;
import com.referboard.referral.web.dto.ReferralDtos.ContactResponse;
import com.referboard.referral.web.dto.ReferralDtos.CreateContactRequest;
import com.referboard.referral.web.dto.ReferralDtos.PipelineSummary;
import com.referboard.referral.web.dto.ReferralDtos.UpdateContactRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The referral CRM: contacts per job and their pipeline. Every status change is recorded and published. */
@Service
public class ContactService {

    static final int DETAIL_DRAFTS = 12;

    private final ContactRepository contacts;
    private final ContactStatusHistoryRepository history;
    private final ReminderRepository reminders;
    private final MessageDraftRepository drafts;
    private final JobSnapshotRepository jobs;
    private final SnapshotService snapshots;
    private final ReminderService reminderService;
    private final ReferralMapper mapper;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final ReferralProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ContactService(ContactRepository contacts, ContactStatusHistoryRepository history,
                          ReminderRepository reminders, MessageDraftRepository drafts, JobSnapshotRepository jobs,
                          SnapshotService snapshots, ReminderService reminderService, ReferralMapper mapper,
                          EventPublisher events, EventJson eventJson, ReferralProperties props,
                          TransactionTemplate tx, Clock clock) {
        this.contacts = contacts;
        this.history = history;
        this.reminders = reminders;
        this.drafts = drafts;
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.reminderService = reminderService;
        this.mapper = mapper;
        this.events = events;
        this.eventJson = eventJson;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Adds someone the user found themselves. The profile URL is validated as text and never requested. */
    public ContactResponse create(UUID userId, UUID jobId, CreateContactRequest req) {
        JobSnapshot job = snapshots.job(jobId);   // 404 for unknown jobs; HTTP call kept outside the transaction
        String url = normalizeUrl(req.profileUrl());
        return tx.execute(status -> {
            if (contacts.countByUserIdAndJobId(userId, jobId) >= props.maxContactsPerJob()) {
                throw ApiException.conflict("CONTACT_LIMIT",
                        "You can track up to " + props.maxContactsPerJob() + " contacts per job.");
            }
            if (url != null && contacts.existsByUserIdAndJobIdAndProfileUrlIgnoreCase(userId, jobId, url)) {
                throw ApiException.conflict("DUPLICATE_CONTACT", "This person is already a contact for this job.");
            }
            Contact contact = new Contact(UUID.randomUUID(), userId, jobId, req.fullName().trim(), url,
                    trimToNull(req.headline()), req.personType());
            contact.setNotes(trimToNull(req.notes()));
            contact = contacts.saveAndFlush(contact);
            history.save(new ContactStatusHistory(contact.getId(), null, ContactStatus.NOT_CONTACTED, clock.instant()));
            return mapper.toResponse(contact, job);
        });
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> listForJob(UUID userId, UUID jobId) {
        JobSnapshot job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return List.of();
        }
        return contacts.findByUserIdAndJobIdOrderByCreatedAtAsc(userId, jobId).stream()
                .map(c -> mapper.toResponse(c, job)).toList();
    }

    @Transactional(readOnly = true)
    public ContactPage list(UUID userId, Collection<ContactStatus> statuses, int page, int size) {
        PageRequest pr = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Contact> result = statuses == null || statuses.isEmpty()
                ? contacts.findByUserId(userId, pr)
                : contacts.findByUserIdAndStatusIn(userId, statuses, pr);
        Map<UUID, JobSnapshot> jobById = jobsFor(result.getContent());
        List<ContactResponse> items = result.getContent().stream()
                .map(c -> mapper.toResponse(c, jobById.get(c.getJobId()))).toList();
        return new ContactPage(items, page, size, result.getTotalElements(), result.hasNext());
    }

    @Transactional(readOnly = true)
    public ContactDetailResponse detail(UUID userId, UUID contactId) {
        Contact c = find(userId, contactId);
        JobSnapshot job = jobs.findById(c.getJobId()).orElse(null);
        return new ContactDetailResponse(
                mapper.toResponse(c, job),
                history.findByContactIdOrderByChangedAtAsc(contactId).stream().map(mapper::toDto).toList(),
                drafts.findByContactIdAndUserIdOrderByCreatedAtDesc(contactId, userId).stream()
                        .limit(DETAIL_DRAFTS).map(mapper::toResponse).toList(),
                reminderService.forContact(userId, c, job));
    }

    @Transactional(readOnly = true)
    public PipelineSummary summary(UUID userId) {
        Map<ContactStatus, Long> by = new EnumMap<>(ContactStatus.class);
        for (ContactStatus s : ContactStatus.values()) {
            by.put(s, 0L);
        }
        long total = 0;
        for (Object[] row : contacts.countByStatus(userId)) {
            long n = ((Number) row[1]).longValue();
            by.put((ContactStatus) row[0], n);
            total += n;
        }
        return new PipelineSummary(by, total, reminders.countByUserIdAndStatus(userId, Reminder.Status.FIRED));
    }

    @Transactional
    public ContactResponse update(UUID userId, UUID contactId, UpdateContactRequest req) {
        Contact c = find(userId, contactId);
        if (req.fullName() != null) {
            if (req.fullName().isBlank()) {
                throw ApiException.badRequest("INVALID_NAME", "Name can't be blank.");
            }
            c.setFullName(req.fullName().trim());
        }
        if (req.profileUrl() != null) {
            String url = req.profileUrl().isBlank() ? null : normalizeUrl(req.profileUrl());
            if (url != null && !url.equalsIgnoreCase(c.getProfileUrl())
                    && contacts.existsByUserIdAndJobIdAndProfileUrlIgnoreCase(userId, c.getJobId(), url)) {
                throw ApiException.conflict("DUPLICATE_CONTACT", "This person is already a contact for this job.");
            }
            c.setProfileUrl(url);
        }
        if (req.headline() != null) {
            c.setHeadline(trimToNull(req.headline()));
        }
        if (req.personType() != null) {
            c.setPersonType(req.personType());
        }
        if (req.notes() != null) {
            c.setNotes(trimToNull(req.notes()));
        }
        contacts.saveAndFlush(c);
        return mapper.toResponse(c, jobs.findById(c.getJobId()).orElse(null));
    }

    /**
     * Moves a contact (the user reports what they did on LinkedIn). Records history, publishes
     * ReferralStatusChanged, retires reminders for the old status and schedules the next one.
     */
    @Transactional
    public ContactResponse changeStatus(UUID userId, UUID contactId, ContactStatus target) {
        Contact c = find(userId, contactId);
        ContactStatus from = c.getStatus();
        if (from == target) {
            return mapper.toResponse(c, jobs.findById(c.getJobId()).orElse(null));
        }
        if (!from.canMoveTo(target)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "Can't move from " + from + " to " + target + ". Allowed: " + from.allowedTargets());
        }
        Instant now = clock.instant();
        c.moveTo(target, now);
        contacts.saveAndFlush(c);   // version check now, before side effects
        history.save(new ContactStatusHistory(c.getId(), from, target, now));
        reminders.obsoleteAllPending(c.getId());
        reminders.completeFiredExcept(c.getId(), target);
        reminderService.scheduleFor(c, now);
        JobSnapshot job = jobs.findById(c.getJobId()).orElseThrow();
        events.publish(new ReferralStatusChangedEvent(eventJson.newMetadata("ReferralStatusChanged"), userId,
                c.getJobId(), job.getTitle(), job.getCompanyName(), job.getLocation(), job.getApplyUrl(),
                c.getId(), c.getFullName(), from.name(), target.name(), now));
        return mapper.toResponse(c, job);
    }

    @Transactional
    public void delete(UUID userId, UUID contactId) {
        Contact c = find(userId, contactId);
        c.setDeletedAt(clock.instant());
        contacts.saveAndFlush(c);
        reminders.obsoleteAllPending(c.getId());
        reminders.dismissFired(c.getId());
    }

    Contact find(UUID userId, UUID contactId) {
        return contacts.findByIdAndUserId(contactId, userId)
                .orElseThrow(() -> ApiException.notFound("Contact", contactId));
    }

    private Map<UUID, JobSnapshot> jobsFor(List<Contact> list) {
        var ids = list.stream().map(Contact::getJobId).collect(Collectors.toSet());
        return jobs.findAllById(ids).stream().collect(Collectors.toMap(JobSnapshot::getJobId, Function.identity()));
    }

    private static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LinkedInUrls.normalizeProfileUrl(raw).orElseThrow(() -> ApiException.badRequest("INVALID_PROFILE_URL",
                "Use a LinkedIn profile link like https://www.linkedin.com/in/name, or another https link."));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
