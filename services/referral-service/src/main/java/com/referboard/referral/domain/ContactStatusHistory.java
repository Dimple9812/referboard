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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contact_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactStatusHistory {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID contactId;

    @Enumerated(EnumType.STRING)
    private ContactStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContactStatus toStatus;

    @Column(nullable = false)
    private Instant changedAt;

    public ContactStatusHistory(UUID contactId, ContactStatus from, ContactStatus to, Instant changedAt) {
        this.id = UUID.randomUUID();
        this.contactId = contactId;
        this.fromStatus = from;
        this.toStatus = to;
        this.changedAt = changedAt;
    }
}
