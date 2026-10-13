package com.referboard.notification.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.notification.domain.Notification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Idempotent inserts: one notification per (user, dedup key); recipients and preferences upserted. */
@Repository
public class NotificationStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NotificationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record NewNotification(UUID userId, Notification.Type type, String title, String body, String link,
                                  Map<String, Object> data, String dedupKey) {
    }

    /** @return the new notification's id, or empty if this dedup key was already used for the user */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> insert(NewNotification n, Instant now) {
        UUID id = UUID.randomUUID();
        int count = jdbc.update("""
                        insert into notifications (id, user_id, type, title, body, link, data, dedup_key, created_at)
                        values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                        on conflict (user_id, dedup_key) do nothing
                        """,
                id, n.userId(), n.type().name(), truncate(n.title(), 300), truncate(n.body(), 4000), n.link(),
                json(n.data()), n.dedupKey(), Timestamp.from(now));
        return count == 1 ? Optional.of(id) : Optional.empty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertRecipient(UUID userId, String email, String fullName) {
        jdbc.update("""
                insert into recipients (user_id, email, full_name) values (?, ?, ?)
                on conflict (user_id) do update set email = excluded.email, full_name = excluded.full_name,
                    updated_at = now()
                """, userId, email, fullName);
    }

    /** Creates default preferences (everything on, fresh unsubscribe token) unless they exist. */
    public void ensurePreferences(UUID userId) {
        jdbc.update("""
                insert into notification_preferences (user_id, email_reminders, email_digest, unsubscribe_token)
                values (?, true, true, ?) on conflict (user_id) do nothing
                """, userId, UUID.randomUUID());
    }

    private String json(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
