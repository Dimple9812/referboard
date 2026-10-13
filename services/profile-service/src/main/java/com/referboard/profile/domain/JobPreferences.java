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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "job_preferences")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobPreferences {

    public enum ExperienceLevel { INTERN, ENTRY, MID, SENIOR, LEAD, PRINCIPAL }

    @Id
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> roles = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> locations = new ArrayList<>();

    private boolean remoteOnly;

    private boolean openToRemote = true;

    private Long minSalaryInr;

    @Enumerated(EnumType.STRING)
    private ExperienceLevel experienceLevel;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static JobPreferences defaults(UUID userId) {
        JobPreferences p = new JobPreferences();
        p.userId = userId;
        return p;
    }
}
