package com.referboard.referral.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Someone the user plans to ask (or has asked) for a referral to one job. Entered by the user, never scraped. */
@Entity
@Table(name = "contacts")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Contact {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private String fullName;

    private String profileUrl;
    private String headline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PersonType personType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContactStatus status = ContactStatus.NOT_CONTACTED;

    private Instant requestSentAt;
    private Instant connectedAt;
    private Instant referralAskedAt;
    private Instant referredAt;
    private Instant noResponseAt;

    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;

    @Version
    private long version;

    public Contact(UUID id, UUID userId, UUID jobId, String fullName, String profileUrl, String headline,
                   PersonType personType) {
        this.id = id;
        this.userId = userId;
        this.jobId = jobId;
        this.fullName = fullName;
        this.profileUrl = profileUrl;
        this.headline = headline;
        this.personType = personType;
    }

    /** Moves to {@code target} and stamps that status's timestamp (the latest entry wins on re-entry). */
    public void moveTo(ContactStatus target, Instant at) {
        this.status = target;
        switch (target) {
            case REQUEST_SENT -> requestSentAt = at;
            case CONNECTED -> connectedAt = at;
            case REFERRAL_ASKED -> referralAskedAt = at;
            case REFERRED -> referredAt = at;
            case NO_RESPONSE -> noResponseAt = at;
            case NOT_CONTACTED -> { }
        }
    }
}
