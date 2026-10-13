package com.referboard.referral.domain;

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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reminders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reminder {

    public enum Type { SEND_REFERRAL_MESSAGE, FOLLOW_UP }

    /** PENDING → FIRED (event sent) → DONE / DISMISSED by the user; OBSOLETE if the contact moved on first. */
    public enum Status { PENDING, FIRED, DONE, DISMISSED, OBSOLETE }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID contactId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContactStatus triggerStatus;

    @Column(nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private String message;
    private String draftedFollowUp;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    private Instant firedAt;

    public Reminder(UUID userId, UUID contactId, Type type, ContactStatus triggerStatus, Instant dueAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.contactId = contactId;
        this.type = type;
        this.triggerStatus = triggerStatus;
        this.dueAt = dueAt;
    }
}
