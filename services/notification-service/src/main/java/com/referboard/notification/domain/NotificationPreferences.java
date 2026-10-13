package com.referboard.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreferences {

    @Id
    private UUID userId;

    private boolean emailReminders;
    private boolean emailDigest;

    @Column(nullable = false, updatable = false)
    private UUID unsubscribeToken;

    @UpdateTimestamp
    private Instant updatedAt;
}
