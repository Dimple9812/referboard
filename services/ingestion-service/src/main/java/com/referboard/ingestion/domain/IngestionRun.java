package com.referboard.ingestion.domain;

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
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ingestion_runs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionRun {

    public enum Trigger { SCHEDULED, MANUAL, STARTUP }

    public enum Status { RUNNING, SUCCEEDED, PARTIAL, FAILED }

    public record SourceError(String sourceKey, String kind, String message) {
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private Trigger trigger;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.RUNNING;

    private int sourcesOk;
    private int sourcesFailed;
    private int fetched;
    private int kept;
    private int created;
    private int updated;
    private int unchanged;
    private int closed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<SourceError> errors = new ArrayList<>();

    public static IngestionRun start(Trigger trigger, Instant now) {
        IngestionRun run = new IngestionRun();
        run.id = UUID.randomUUID();
        run.trigger = trigger;
        run.startedAt = now;
        return run;
    }
}
