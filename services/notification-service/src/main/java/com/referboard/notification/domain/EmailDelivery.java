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

import java.time.Instant;
import java.util.UUID;

/** A rendered email waiting to be sent (QUEUED), or the record of what happened to it. */
@Entity
@Table(name = "email_deliveries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailDelivery {

    public enum Status { QUEUED, SENT, FAILED, SKIPPED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    private UUID notificationId;

    @Column(nullable = false)
    private String template;

    @Column(nullable = false)
    private String toAddress;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String htmlBody;

    @Column(nullable = false)
    private String textBody;

    private String unsubscribeUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    private String skipReason;
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private String lastError;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant sentAt;

    public EmailDelivery(UUID userId, UUID notificationId, String template, String toAddress,
                         com.referboard.notification.email.RenderedEmail email, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.notificationId = notificationId;
        this.template = template;
        this.toAddress = toAddress;
        this.subject = email.subject();
        this.htmlBody = email.html();
        this.textBody = email.text();
        this.unsubscribeUrl = email.unsubscribeUrl();
        this.nextAttemptAt = now;
    }

    public void skip(String reason) {
        this.status = Status.SKIPPED;
        this.skipReason = reason;
    }
}
