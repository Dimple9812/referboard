package com.referboard.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * What ingestion knows about one de-duplicated job: when it was first/last seen, whether it's closed, and the
 * content hash of the version last published (so unchanged postings produce no event).
 */
@Entity
@Table(name = "ingested_postings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestedPosting implements Persistable<UUID> {

    @Id
    private UUID jobId;

    @Column(nullable = false)
    private String dedupKey;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String sourceKey;

    @Column(nullable = false)
    private String sourceJobId;

    @Column(nullable = false)
    private String companyName;

    @Column(nullable = false)
    private String title;

    private String location;

    @Column(nullable = false)
    private String contentHash;

    private Instant postedAt;

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    private Instant closedAt;

    private int repostCount;

    private boolean ghostJobSuspected;

    private String embeddingModel;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @jakarta.persistence.Transient
    private boolean isNew;

    public static IngestedPosting firstSeen(UUID jobId, String dedupKey, Instant now) {
        IngestedPosting p = new IngestedPosting();
        p.jobId = jobId;
        p.dedupKey = dedupKey;
        p.firstSeenAt = now;
        p.lastSeenAt = now;
        p.isNew = true;
        return p;
    }

    @Override
    public UUID getId() {
        return jobId;
    }

    /** Lets Spring Data insert directly (no select-before-insert) for brand-new rows with assigned ids. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isOpen() {
        return closedAt == null;
    }
}
