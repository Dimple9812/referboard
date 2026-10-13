package com.referboard.tracker.domain;

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
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One card on the user's board. Job fields are a copy taken when the card was created (jobs from ReferBoard) or
 * typed by the user (jobs found elsewhere, {@code jobId == null}). Rows are inserted with SQL (see
 * {@code ApplicationInserts}) so concurrent creates for the same job resolve to one card.
 */
@Entity
@Table(name = "applications")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Application {

    public enum Source { MANUAL, REFERRAL }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    private UUID jobId;

    @Column(nullable = false)
    private String jobTitle;

    @Column(nullable = false)
    private String companyName;

    private String location;
    private String applyUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage;

    private double position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private UUID resumeVersionId;
    private String referralSummary;
    private String notes;
    private Instant appliedAt;

    @Column(nullable = false)
    private Instant stageChangedAt;

    private String nextAction;
    private Instant nextActionAt;
    private Instant jobClosedAt;

    @Column(updatable = false, insertable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;

    @Version
    private long version;

    /** Changes column; the first move to APPLIED records the application date (editable afterwards). */
    public void moveTo(Stage target, Instant at) {
        this.stage = target;
        this.stageChangedAt = at;
        if (target == Stage.APPLIED && appliedAt == null) {
            appliedAt = at;
        }
    }
}
