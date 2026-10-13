package com.referboard.notification.service;

import com.referboard.common.web.ApiException;
import com.referboard.notification.domain.Notification;
import com.referboard.notification.domain.NotificationPreferences;
import com.referboard.notification.repository.NotificationRepository;
import com.referboard.notification.repository.NotificationStore;
import com.referboard.notification.repository.PreferencesRepository;
import com.referboard.notification.web.dto.NotificationDtos.MarkedRead;
import com.referboard.notification.web.dto.NotificationDtos.NotificationPage;
import com.referboard.notification.web.dto.NotificationDtos.NotificationResponse;
import com.referboard.notification.web.dto.NotificationDtos.Preferences;
import com.referboard.notification.web.dto.NotificationDtos.UpdatePreferences;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** The in-app notification inbox and email preferences. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final PreferencesRepository preferences;
    private final NotificationStore store;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, PreferencesRepository preferences,
                               NotificationStore store, Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationPage list(UUID userId, boolean unreadOnly, int page, int size) {
        PageRequest pr = PageRequest.of(page, size);
        Page<Notification> result = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pr)
                : notifications.findByUserIdOrderByCreatedAtDesc(userId, pr);
        return new NotificationPage(result.getContent().stream().map(NotificationService::toResponse).toList(),
                notifications.countByUserIdAndReadAtIsNull(userId), page, size, result.getTotalElements(),
                result.hasNext());
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public NotificationResponse markRead(UUID userId, UUID id) {
        Notification n = find(userId, id);
        if (n.getReadAt() == null) {
            n.setReadAt(clock.instant());
        }
        return toResponse(n);
    }

    @Transactional
    public MarkedRead markAllRead(UUID userId) {
        return new MarkedRead(notifications.markAllRead(userId, clock.instant()));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        find(userId, id).setDeletedAt(clock.instant());
    }

    @Transactional
    public Preferences preferences(UUID userId) {
        return toDto(prefs(userId));
    }

    @Transactional
    public Preferences updatePreferences(UUID userId, UpdatePreferences req) {
        NotificationPreferences p = prefs(userId);
        if (req.emailReminders() != null) {
            p.setEmailReminders(req.emailReminders());
        }
        if (req.emailDigest() != null) {
            p.setEmailDigest(req.emailDigest());
        }
        return toDto(p);
    }

    /**
     * One-click unsubscribe from an email link: the token identifies the user, no login needed.
     *
     * @return the category label for the confirmation page, or empty for an unknown token
     */
    @Transactional
    public Optional<String> unsubscribe(UUID token, EmailCategory category) {
        return preferences.findByUnsubscribeToken(token).map(p -> {
            if (category == null) {
                p.setEmailReminders(false);
                p.setEmailDigest(false);
                return "all ReferBoard emails";
            }
            if (category == EmailCategory.REMINDERS) {
                p.setEmailReminders(false);
            } else {
                p.setEmailDigest(false);
            }
            return category.label();
        });
    }

    private NotificationPreferences prefs(UUID userId) {
        store.ensurePreferences(userId);
        return preferences.findById(userId).orElseThrow();
    }

    private Notification find(UUID userId, UUID id) {
        return notifications.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Notification", id));
    }

    private static Preferences toDto(NotificationPreferences p) {
        return new Preferences(p.isEmailReminders(), p.isEmailDigest());
    }

    static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLink(), n.getData(),
                n.getReadAt() != null, n.getReadAt(), n.getCreatedAt());
    }
}
