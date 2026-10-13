package com.referboard.ingestion.domain;

import com.referboard.ingestion.source.SourceType;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "sources")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Source {

    public enum Status { NEW, OK, FAILING, NOT_FOUND, BLOCKED }

    @Id
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String displayName;

    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NEW;

    private Instant lastAttemptAt;
    private Instant lastSuccessAt;
    private String lastError;
    private int consecutiveFailures;
    private Integer lastJobCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Source of(String key, SourceType type, String slug, String displayName) {
        Source s = new Source();
        s.sourceKey = key;
        s.sourceType = type;
        s.slug = slug;
        s.displayName = displayName;
        return s;
    }

    /** NOT_FOUND / BLOCKED boards are re-checked only occasionally rather than hammered every run. */
    public boolean dueForFetch(Instant now, Duration retryNotFoundAfter) {
        if (!enabled) {
            return false;
        }
        if (status == Status.NOT_FOUND || status == Status.BLOCKED) {
            return lastAttemptAt == null || lastAttemptAt.plus(retryNotFoundAfter).isBefore(now);
        }
        return true;
    }

    public void recordSuccess(Instant now, int jobCount) {
        lastAttemptAt = now;
        lastSuccessAt = now;
        status = Status.OK;
        lastError = null;
        consecutiveFailures = 0;
        lastJobCount = jobCount;
    }

    public void recordFailure(Instant now, Status newStatus, String error) {
        lastAttemptAt = now;
        status = newStatus;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        consecutiveFailures++;
    }
}
