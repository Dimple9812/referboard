package com.referboard.tracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The card's timeline. Append-only. */
@Entity
@Table(name = "application_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationEvent {

    public enum Type { CREATED, STAGE_CHANGED, NOTE, RESUME_LINKED, REFERRAL_UPDATE, JOB_CLOSED, UPDATED }

    @Id
    private UUID id;

    /** Database-assigned insertion order (the timeline's sort key). */
    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(nullable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    private Stage fromStage;

    @Enumerated(EnumType.STRING)
    private Stage toStage;

    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> details;

    @Column(nullable = false)
    private Instant occurredAt;

    public ApplicationEvent(UUID applicationId, Type type, Stage from, Stage to, String message,
                            Map<String, Object> details, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.type = type;
        this.fromStage = from;
        this.toStage = to;
        this.message = message;
        this.details = details == null ? Map.of() : details;
        this.occurredAt = occurredAt;
    }

    public static ApplicationEvent of(UUID applicationId, Type type, String message, Instant at) {
        return new ApplicationEvent(applicationId, type, null, null, message, Map.of(), at);
    }
}
