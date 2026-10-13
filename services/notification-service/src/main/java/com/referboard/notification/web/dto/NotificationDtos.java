package com.referboard.notification.web.dto;

import com.referboard.notification.domain.Notification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(
            UUID id,
            Notification.Type type,
            String title,
            String body,
            @Schema(description = "App path to open, e.g. /referrals/contacts/{id}") String link,
            Map<String, Object> data,
            boolean read,
            Instant readAt,
            Instant createdAt
    ) {
    }

    public record NotificationPage(List<NotificationResponse> items, long unreadCount, int page, int size, long total,
                                   boolean hasNext) {
    }

    public record UnreadCount(long count) {
    }

    public record MarkedRead(int updated) {
    }

    public record Preferences(
            @Schema(description = "Email me referral reminders (connected → send message, no reply → follow up)")
            boolean emailReminders,
            @Schema(description = "Email me the daily top-10 new matches") boolean emailDigest
    ) {
    }

    /** Partial update. */
    public record UpdatePreferences(Boolean emailReminders, Boolean emailDigest) {
    }
}
