package com.referboard.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prep_items")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrepItem {

    public enum Confidence { NEW, PRACTISING, CONFIDENT }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pack_id")
    private PrepPack pack;

    private int position;
    private String section;
    private String question;
    private String checks;
    private String tip;
    private String evidence;
    private String evidenceFrom;
    private String skill;
    private String origin;
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Confidence confidence = Confidence.NEW;

    private Instant updatedAt;

    @Version
    private long version;

    public PrepItem(UUID id, PrepPack pack, int position) {
        this.id = id;
        this.pack = pack;
        this.position = position;
    }
}
