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

/**
 * A tailored resume: the structured content after the accepted suggestions. Files are rendered from it on
 * download, in either format, so nothing binary is stored.
 */
@Entity
@Table(name = "resume_versions")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResumeVersion {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    private UUID jobId;
    private UUID analysisId;

    @Column(nullable = false)
    private String baseSource;

    @Column(nullable = false)
    private UUID baseRefId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String acceptedSuggestions;

    @Column(nullable = false)
    private String fileFormat;

    private Short scoreBefore;
    private Short scoreAfter;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant deletedAt;

    public ResumeVersion(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
    }
}
