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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "experiences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Experience {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private Profile profile;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false)
    private String title;

    private String location;

    /** First day of the start month. */
    private LocalDate startDate;

    /** First day of the end month; null while current. */
    private LocalDate endDate;

    @Column(name = "is_current")
    private boolean current;

    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> bullets = new ArrayList<>();

    private int sortOrder;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Experience of(String company, String title, String location, LocalDate startDate,
                                LocalDate endDate, boolean current, String description, List<String> bullets,
                                int sortOrder) {
        Experience e = new Experience();
        e.id = UUID.randomUUID();
        e.company = company;
        e.title = title;
        e.location = location;
        e.startDate = startDate;
        e.endDate = current ? null : endDate;
        e.current = current;
        e.description = description;
        e.bullets = bullets == null ? new ArrayList<>() : new ArrayList<>(bullets);
        e.sortOrder = sortOrder;
        return e;
    }

    void attachTo(Profile owner) {
        this.profile = owner;
    }
}
