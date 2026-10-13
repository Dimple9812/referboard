package com.referboard.tracker.repository;

import com.referboard.tracker.domain.Application;
import com.referboard.tracker.domain.Stage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Card creation and the small tables, in SQL. {@code on conflict do nothing} against the partial unique index on
 * (user_id, job_id) makes "create the card for this job" idempotent when the user and a Kafka event race.
 */
@Repository
public class ApplicationInserts {

    private final JdbcTemplate jdbc;

    public ApplicationInserts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NewCard(UUID userId, UUID jobId, String jobTitle, String companyName, String location,
                          String applyUrl, Stage stage, double position, Application.Source source, Instant appliedAt,
                          String notes) {
    }

    /** @return the new card's id, or empty if the user already has a card for this job */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> insert(NewCard c, Instant now) {
        UUID id = UUID.randomUUID();
        Timestamp ts = Timestamp.from(now);
        int n = jdbc.update("""
                        insert into applications (id, user_id, job_id, job_title, company_name, location, apply_url,
                            stage, position, source, applied_at, notes, stage_changed_at, created_at, updated_at, version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        on conflict (user_id, job_id) where deleted_at is null and job_id is not null do nothing
                        """,
                id, c.userId(), c.jobId(), c.jobTitle(), c.companyName(), c.location(), c.applyUrl(),
                c.stage().name(), c.position(), c.source().name(),
                c.appliedAt() == null ? null : Timestamp.from(c.appliedAt()), c.notes(), ts, ts, ts);
        return n == 1 ? Optional.of(id) : Optional.empty();
    }

    public record PendingResume(UUID resumeVersionId, String versionName) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void savePendingResume(UUID userId, UUID jobId, UUID resumeVersionId, String versionName) {
        jdbc.update("""
                insert into resume_links_pending (user_id, job_id, resume_version_id, version_name)
                values (?, ?, ?, ?)
                on conflict (user_id, job_id) do update set resume_version_id = excluded.resume_version_id,
                    version_name = excluded.version_name, created_at = now()
                """, userId, jobId, resumeVersionId, versionName);
    }

    /** Removes and returns the pending resume link for this job, if any. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PendingResume> takePendingResume(UUID userId, UUID jobId) {
        return jdbc.query("""
                        delete from resume_links_pending where user_id = ? and job_id = ?
                        returning resume_version_id, version_name
                        """,
                (rs, i) -> new PendingResume(rs.getObject(1, UUID.class), rs.getString(2)), userId, jobId)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordClosedJob(UUID jobId, Instant closedAt) {
        jdbc.update("insert into closed_jobs (job_id, closed_at) values (?, ?) on conflict (job_id) do nothing",
                jobId, Timestamp.from(closedAt));
    }

    public Optional<Instant> closedAt(UUID jobId) {
        return jdbc.query("select closed_at from closed_jobs where job_id = ?",
                (rs, i) -> rs.getTimestamp(1).toInstant(), jobId).stream().findFirst();
    }
}
