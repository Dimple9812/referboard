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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The user's own facts from profile-service, so reminder follow-ups can be drafted by the scheduler with no
 * user token. Only what drafts need; achievements are resume bullets copied verbatim.
 */
@Entity
@Table(name = "user_snapshots")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSnapshot {

    @Id
    private UUID userId;

    private String fullName;
    private String headline;
    private String currentTitle;
    private String currentCompany;
    private BigDecimal experienceYears;
    private String college;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> pastCompanies = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> skills = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> achievements = new ArrayList<>();

    @Column(nullable = false)
    private Instant fetchedAt;

    public UserSnapshot(UUID userId) {
        this.userId = userId;
    }
}
