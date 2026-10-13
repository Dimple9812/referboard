package com.referboard.notification.messaging;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.ProcessedEventStore;
import com.referboard.events.DailyMatchDigestEvent;
import com.referboard.events.ReferralReminderDueEvent;
import com.referboard.events.Topics;
import com.referboard.events.UserRegisteredEvent;
import com.referboard.notification.domain.Notification;
import com.referboard.notification.email.EmailTemplates;
import com.referboard.notification.repository.NotificationStore;
import com.referboard.notification.repository.NotificationStore.NewNotification;
import com.referboard.notification.service.EmailCategory;
import com.referboard.notification.service.NotificationWriter;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns events into notifications (and queued emails). Each handler is one transaction that records the event id
 * first; failures retry with backoff, then go to {@code <topic>.DLT}.
 */
@Component
public class NotificationConsumers {

    private final EventJson json;
    private final ProcessedEventStore processed;
    private final NotificationStore store;
    private final NotificationWriter writer;
    private final EmailTemplates templates;

    public NotificationConsumers(EventJson json, ProcessedEventStore processed, NotificationStore store,
                                 NotificationWriter writer, EmailTemplates templates) {
        this.json = json;
        this.processed = processed;
        this.store = store;
        this.writer = writer;
        this.templates = templates;
    }

    @KafkaListener(topics = Topics.USER_REGISTERED, groupId = "notification-service")
    @Transactional
    public void onUserRegistered(String payload) {
        UserRegisteredEvent e = json.read(payload, UserRegisteredEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.USER_REGISTERED)) {
            return;
        }
        store.upsertRecipient(e.userId(), e.email(), e.fullName());
        store.ensurePreferences(e.userId());
        writer.notify(new NewNotification(e.userId(), Notification.Type.WELCOME, "Welcome to ReferBoard",
                "Upload your resume to see how well each job fits you, then find people who can refer you.",
                "/onboarding", Map.of(), "welcome"), null, null, null);
    }

    @KafkaListener(topics = Topics.REFERRAL_REMINDER_DUE, groupId = "notification-service")
    @Transactional
    public void onReminderDue(String payload) {
        ReferralReminderDueEvent e = json.read(payload, ReferralReminderDueEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.REFERRAL_REMINDER_DUE)) {
            return;
        }
        boolean sendReferral = "SEND_REFERRAL_MESSAGE".equals(e.reminderType());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reminderId", e.reminderId().toString());
        data.put("contactId", e.contactId().toString());
        data.put("jobId", e.jobId().toString());
        data.put("reminderType", e.reminderType());
        if (e.draftedFollowUp() != null) {
            data.put("draftedMessage", e.draftedFollowUp());
        }
        String title = sendReferral ? "Send your referral message to " + e.contactName()
                : "Follow up with " + e.contactName();
        writer.notify(new NewNotification(e.userId(), Notification.Type.REFERRAL_REMINDER, title, e.message(),
                        "/referrals/contacts/" + e.contactId(), data, "reminder:" + e.reminderId()),
                EmailCategory.REMINDERS, "referral-reminder",
                (firstName, unsubscribe) -> templates.reminder(firstName, e, unsubscribe));
    }

    @KafkaListener(topics = Topics.DAILY_MATCH_DIGEST, groupId = "notification-service")
    @Transactional
    public void onDailyDigest(String payload) {
        DailyMatchDigestEvent e = json.read(payload, DailyMatchDigestEvent.class);
        if (!processed.markProcessed(e.metadata().eventId(), Topics.DAILY_MATCH_DIGEST)) {
            return;
        }
        if (e.matches() == null || e.matches().isEmpty()) {
            return;
        }
        List<DailyMatchDigestEvent.MatchSummary> top = e.matches();
        String body = top.stream().limit(3)
                .map(m -> m.matchPercent() + "% " + m.title() + " at " + m.companyName())
                .collect(Collectors.joining(" · "))
                + (top.size() > 3 ? " and " + (top.size() - 3) + " more" : "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("digestDate", e.digestDate().toString());
        data.put("jobIds", top.stream().map(m -> m.jobId().toString()).toList());
        writer.notify(new NewNotification(e.userId(), Notification.Type.DAILY_DIGEST,
                        top.size() + (top.size() == 1 ? " new job match" : " new job matches") + " for you", body,
                        "/jobs?view=recommended", data, "digest:" + e.digestDate()),
                EmailCategory.DIGEST, "daily-digest",
                (firstName, unsubscribe) -> templates.digest(firstName, e, unsubscribe));
    }
}
