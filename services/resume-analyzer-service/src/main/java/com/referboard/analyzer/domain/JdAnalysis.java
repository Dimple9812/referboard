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

/** One analysis. The full report is stored as JSON (exactly what the API returned) beside queryable columns. */
@Entity
@Table(name = "jd_analyses")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JdAnalysis {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    private UUID jobId;
    private String jobTitle;
    private String companyName;

    @Column(nullable = false)
    private String jdSource;

    @Column(nullable = false)
    private String jdHash;

    @Column(nullable = false)
    private String jdText;

    @Column(nullable = false)
    private String resumeSource;

    @Column(nullable = false)
    private UUID resumeRefId;

    private String resumeName;
    private short score;

    @Column(nullable = false)
    private String verdict;

    private short projectedScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String report;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String resumeContent;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant deletedAt;

    public JdAnalysis(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
    }
}
