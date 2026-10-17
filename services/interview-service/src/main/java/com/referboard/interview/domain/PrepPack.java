package com.referboard.interview.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "prep_packs")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrepPack {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID analysisId;

    private UUID jobId;
    private String jobTitle;
    private String companyName;

    @Column(nullable = false)
    private String origin;

    private String model;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant deletedAt;

    @OneToMany(mappedBy = "pack", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<PrepItem> items = new ArrayList<>();

    public PrepPack(UUID id, UUID userId, UUID analysisId) {
        this.id = id;
        this.userId = userId;
        this.analysisId = analysisId;
    }
}
