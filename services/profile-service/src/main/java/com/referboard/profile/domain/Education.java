package com.referboard.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "educations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Education {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private Profile profile;

    @Column(nullable = false)
    private String institution;

    private String degree;
    private String fieldOfStudy;
    private Integer startYear;
    private Integer endYear;
    private String grade;
    private int sortOrder;

    public static Education of(String institution, String degree, String fieldOfStudy, Integer startYear,
                               Integer endYear, String grade, int sortOrder) {
        Education e = new Education();
        e.id = UUID.randomUUID();
        e.institution = institution;
        e.degree = degree;
        e.fieldOfStudy = fieldOfStudy;
        e.startYear = startYear;
        e.endYear = endYear;
        e.grade = grade;
        e.sortOrder = sortOrder;
        return e;
    }

    void attachTo(Profile owner) {
        this.profile = owner;
    }
}
