package com.referboard.referral.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The job facts drafts are built from, copied from job-service (we never read its tables). */
@Entity
@Table(name = "job_snapshots")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobSnapshot {

    @Id
    private UUID jobId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String companyName;

    private String location;
    private String applyUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> skills = new ArrayList<>();

    private String descriptionExcerpt;

    @Column(nullable = false)
    private Instant fetchedAt;

    public JobSnapshot(UUID jobId) {
        this.jobId = jobId;
    }
}
