package com.referboard.notification.service;

import com.referboard.notification.config.NotificationProperties;
import com.referboard.notification.domain.EmailDelivery;
import com.referboard.notification.domain.NotificationPreferences;
import com.referboard.notification.domain.Recipient;
import com.referboard.notification.email.RenderedEmail;
import com.referboard.notification.repository.EmailDeliveryRepository;
import com.referboard.notification.repository.NotificationStore;
import com.referboard.notification.repository.PreferencesRepository;
import com.referboard.notification.repository.RecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Creates an in-app notification and, when the category allows, queues its email in the same transaction. The
 * dedup key makes this idempotent: a repeated reminder or digest produces neither a second notification nor a
 * second email.
 */
@Service
public class NotificationWriter {

    private static final Logger log = LoggerFactory.getLogger(NotificationWriter.class);

    private final NotificationStore store;
    private final RecipientRepository recipients;
    private final PreferencesRepository preferences;
    private final EmailDeliveryRepository deliveries;
    private final NotificationProperties props;
    private final Clock clock;

    public NotificationWriter(NotificationStore store, RecipientRepository recipients,
                              PreferencesRepository preferences, EmailDeliveryRepository deliveries,
                              NotificationProperties props, Clock clock) {
        this.store = store;
        this.recipients = recipients;
        this.preferences = preferences;
        this.deliveries = deliveries;
        this.props = props;
        this.clock = clock;
    }

    /**
     * @param category null for in-app only
     * @param render   (first name, unsubscribe URL) → email
     * @return the notification id, or empty if it already existed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> notify(NotificationStore.NewNotification n, EmailCategory category, String template,
                                 BiFunction<String, String, RenderedEmail> render) {
        Instant now = clock.instant();
        Optional<UUID> id = store.insert(n, now);
        if (id.isEmpty() || category == null) {
            return id;
        }
        Optional<Recipient> recipient = recipients.findById(n.userId());
        if (recipient.isEmpty()) {
            log.info("No email address known for user {} (UserRegistered not seen); in-app only", n.userId());
            return id;
        }
        store.ensurePreferences(n.userId());
        NotificationPreferences prefs = preferences.findById(n.userId()).orElseThrow();
        String unsubscribeUrl = props.apiLink("/api/notifications/unsubscribe?token=" + prefs.getUnsubscribeToken()
                + "&category=" + category.param());
        RenderedEmail email = render.apply(recipient.get().firstName(), unsubscribeUrl);
        EmailDelivery d = new EmailDelivery(n.userId(), id.get(), template, recipient.get().getEmail(), email, now);
        boolean enabled = category == EmailCategory.REMINDERS ? prefs.isEmailReminders() : prefs.isEmailDigest();
        if (!enabled) {
            d.skip("OPTED_OUT");
        } else if (emailsToday(n.userId()) >= props.maxEmailsPerUserPerDay()) {
            d.skip("DAILY_LIMIT");
            log.warn("User {} reached the daily email limit ({}); '{}' not emailed", n.userId(),
                    props.maxEmailsPerUserPerDay(), email.subject());
        }
        deliveries.save(d);
        return id;
    }

    private long emailsToday(UUID userId) {
        Instant startOfDay = LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().toInstant(ZoneOffset.UTC);
        return deliveries.countByUserIdAndStatusInAndCreatedAtAfter(userId,
                EnumSet.of(EmailDelivery.Status.QUEUED, EmailDelivery.Status.SENT, EmailDelivery.Status.FAILED),
                startOfDay);
    }
}
