package com.referboard.analyzer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.UUID;

/** An interview prep plan for one analysis; the items are stored as JSON exactly as the API returns them. */
@Entity
@Table(name = "interview_preps")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewPrep {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    private UUID analysisId;
    private UUID jobId;
    private String jobTitle;
    private String companyName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String items;

    private boolean aiQuestions;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant deletedAt;

    public InterviewPrep(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
    }
}
