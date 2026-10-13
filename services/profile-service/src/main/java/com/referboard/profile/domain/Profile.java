package com.referboard.profile.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The candidate profile: one per user, keyed by user id. The embedding column is written separately with native
 * SQL (see {@code ProfileEmbeddingRepository}) and is not mapped here.
 */
@Entity
@Table(name = "profiles")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Profile {

    public enum Source { LLM, HEURISTIC, MANUAL }

    @Id
    private UUID userId;

    private String fullName;
    private String email;
    private String phone;
    private String headline;
    private String summary;
    private String currentTitle;
    private String currentCompany;
    private String location;
    private BigDecimal totalExperienceYears;
    private String college;
    private String highestDegree;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> links = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> certifications = new ArrayList<>();

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Source parseSource;

    private UUID primaryResumeId;

    /** Bumped on every meaningful change; carried in ProfileUpdated so consumers can drop stale events. */
    private long profileVersion;

    private String embeddingModel;

    private Long embeddingVersion;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<ProfileSkill> skills = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<Experience> experiences = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<Education> educations = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Profile create(UUID userId) {
        Profile profile = new Profile();
        profile.userId = userId;
        return profile;
    }

    public void bumpVersion() {
        profileVersion++;
    }

    public void replaceSkills(List<ProfileSkill> next) {
        skills.clear();
        next.forEach(s -> s.attachTo(this));
        skills.addAll(next);
    }

    public void replaceExperiences(List<Experience> next) {
        experiences.clear();
        next.forEach(e -> e.attachTo(this));
        experiences.addAll(next);
    }

    public void replaceEducations(List<Education> next) {
        educations.clear();
        next.forEach(e -> e.attachTo(this));
        educations.addAll(next);
    }
}
