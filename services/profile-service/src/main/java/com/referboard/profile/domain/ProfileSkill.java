package com.referboard.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profile_skills")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProfileSkill {

    /** PARSED = came from a resume; USER = added by the user. Re-parsing a resume never removes USER skills. */
    public enum Source { PARSED, USER }

    /** BULLET = backed by an experience bullet; SKILLS_LIST = only listed (weak evidence for ATS/recruiters). */
    public enum Evidence { BULLET, SKILLS_LIST }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private Profile profile;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String displayName;

    private BigDecimal years;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Evidence evidence;

    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static ProfileSkill of(String name, String displayName, BigDecimal years, Source source,
                                  Evidence evidence, int sortOrder) {
        ProfileSkill skill = new ProfileSkill();
        skill.id = UUID.randomUUID();
        skill.name = name;
        skill.displayName = displayName;
        skill.years = years;
        skill.source = source;
        skill.evidence = evidence;
        skill.sortOrder = sortOrder;
        return skill;
    }

    void attachTo(Profile owner) {
        this.profile = owner;
    }
}
