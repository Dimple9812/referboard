package com.referboard.profile.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "resumes")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Resume {

    public enum ParseStatus { PENDING, PARSED, FAILED }

    public enum Parser { LLM, HEURISTIC }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String contentType;

    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Column(nullable = false)
    private String storageKey;

    private String extractedText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParseStatus parseStatus = ParseStatus.PENDING;

    private String parseError;

    @Enumerated(EnumType.STRING)
    private Parser parser;

    private String parserModel;

    @JdbcTypeCode(SqlTypes.JSON)
    private String parsedJson;

    private Instant parsedAt;

    @Column(name = "is_primary")
    private boolean primary;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deletedAt;

    public static Resume uploaded(UUID id, UUID userId, String fileName, String contentType, long size,
                                  String sha256, String storageKey) {
        Resume resume = new Resume();
        resume.id = id;
        resume.userId = userId;
        resume.fileName = fileName;
        resume.contentType = contentType;
        resume.sizeBytes = size;
        resume.sha256 = sha256;
        resume.storageKey = storageKey;
        return resume;
    }

    public void markParsed(String text, Parser parser, String model, String parsedJson, Instant now) {
        this.extractedText = text;
        this.parser = parser;
        this.parserModel = model;
        this.parsedJson = parsedJson;
        this.parsedAt = now;
        this.parseStatus = ParseStatus.PARSED;
        this.parseError = null;
    }

    public void markFailed(String reason) {
        this.parseStatus = ParseStatus.FAILED;
        this.parseError = reason == null ? "Unknown error" : reason.substring(0, Math.min(reason.length(), 1000));
    }

    public void resetForReparse() {
        this.parseStatus = ParseStatus.PENDING;
        this.parseError = null;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
        this.primary = false;
    }
}
