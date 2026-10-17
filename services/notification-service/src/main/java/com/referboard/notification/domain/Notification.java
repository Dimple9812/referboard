package com.referboard.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An in-app notification. Inserted with SQL (see NotificationStore) so each dedup key yields one row. */
@Entity
@Table(name = "notifications")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    public enum Type { REFERRAL_REMINDER, DAILY_DIGEST, WELCOME, SYSTEM, BILLING, INTERVIEW }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    private String link;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> data;

    @Column(nullable = false, updatable = false)
    private String dedupKey;

    private Instant readAt;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant deletedAt;
}
