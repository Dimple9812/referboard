package com.referboard.analyzer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/** A cover letter: the generated draft and the text the user last saved. Files are rendered on download. */
@Entity
@Table(name = "cover_letters")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoverLetter {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    private UUID analysisId;
    private UUID jobId;
    private String jobTitle;
    private String companyName;

    @Column(nullable = false)
    private String length;

    @Column(nullable = false)
    private String origin;

    private String model;
    private String motivation;
    private String senderName;
    private String senderContact;

    @Column(nullable = false, updatable = false)
    private String generatedBody;

    @Column(nullable = false)
    private String body;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;
    private Instant deletedAt;

    @Version
    private long version;

    public CoverLetter(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
    }
}
