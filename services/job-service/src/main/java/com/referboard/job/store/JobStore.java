package com.referboard.job.store;

import com.referboard.events.JobUpsertedEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Writes to the jobs catalogue. All methods join the caller's transaction (the idempotent consumer's). */
@Repository
public class JobStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JobStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upserts a job from a JobUpserted event. Embedding rule (see the event contract): a null embedding keeps the
     * stored vector if the content hash is unchanged, otherwise clears it.
     *
     * @return true if the job is new or was closed and is now reopened
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean upsert(JobUpsertedEvent e) {
        UUID companyId = upsertCompany(e.companyName());
        Instant listedAt = e.postedAt() != null ? e.postedAt() : e.firstSeenAt();
        Boolean wasOpen = jdbc.query("select status = 'OPEN' from jobs where id = :id", new MapSqlParameterSource("id", e.jobId()),
                rs -> rs.next() ? rs.getBoolean(1) : null);

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", e.jobId())
                .addValue("dedupKey", e.dedupKey())
                .addValue("source", e.source())
                .addValue("sourceJobId", e.sourceJobId())
                .addValue("companyId", companyId)
                .addValue("companyName", e.companyName())
                .addValue("title", e.title())
                .addValue("normalizedTitle", e.normalizedTitle() == null ? e.title().toLowerCase(Locale.ROOT) : e.normalizedTitle())
                .addValue("location", e.location())
                .addValue("country", e.country())
                .addValue("remote", e.remote())
                .addValue("employmentType", e.employmentType())
                .addValue("seniority", e.seniority())
                .addValue("department", e.departmentName())
                .addValue("descriptionHtml", e.descriptionHtml())
                .addValue("descriptionText", e.descriptionText())
                .addValue("applyUrl", e.applyUrl())
                .addValue("salaryMin", e.salaryMin())
                .addValue("salaryMax", e.salaryMax())
                .addValue("salaryCurrency", e.salaryCurrency())
                .addValue("postedAt", ts(e.postedAt()))
                .addValue("firstSeenAt", ts(e.firstSeenAt()))
                .addValue("lastSeenAt", ts(e.lastSeenAt()))
                .addValue("listedAt", ts(listedAt))
                .addValue("ghost", e.ghostJobSuspected())
                .addValue("hash", e.contentHash())
                .addValue("embedding", Vectors.literal(e.embedding()), java.sql.Types.VARCHAR)
                .addValue("embeddingModel", e.embeddingModel());

        jdbc.update("""
                insert into jobs (id, dedup_key, source, source_job_id, company_id, company_name, title, normalized_title,
                                  location, country, remote, employment_type, seniority, department, description_html,
                                  description_text, apply_url, salary_min, salary_max, salary_currency, posted_at,
                                  first_seen_at, last_seen_at, listed_at, status, closed_at, ghost_job_suspected,
                                  content_hash, embedding, embedding_model)
                values (:id, :dedupKey, :source, :sourceJobId, :companyId, :companyName, :title, :normalizedTitle,
                        :location, :country, :remote, :employmentType, :seniority, :department, :descriptionHtml,
                        :descriptionText, :applyUrl, :salaryMin, :salaryMax, :salaryCurrency, :postedAt,
                        :firstSeenAt, :lastSeenAt, :listedAt, 'OPEN', null, :ghost,
                        :hash, cast(:embedding as public.vector), :embeddingModel)
                on conflict (id) do update set
                    dedup_key = excluded.dedup_key, source = excluded.source, source_job_id = excluded.source_job_id,
                    company_id = excluded.company_id, company_name = excluded.company_name, title = excluded.title,
                    normalized_title = excluded.normalized_title, location = excluded.location,
                    country = excluded.country, remote = excluded.remote, employment_type = excluded.employment_type,
                    seniority = excluded.seniority, department = excluded.department,
                    description_html = excluded.description_html, description_text = excluded.description_text,
                    apply_url = excluded.apply_url, salary_min = excluded.salary_min, salary_max = excluded.salary_max,
                    salary_currency = excluded.salary_currency, posted_at = excluded.posted_at,
                    first_seen_at = excluded.first_seen_at, last_seen_at = excluded.last_seen_at,
                    listed_at = excluded.listed_at, status = 'OPEN', closed_at = null,
                    ghost_job_suspected = excluded.ghost_job_suspected,
                    embedding = case when excluded.embedding is null and jobs.content_hash = excluded.content_hash
                                     then jobs.embedding else excluded.embedding end,
                    embedding_model = case when excluded.embedding is null and jobs.content_hash = excluded.content_hash
                                           then jobs.embedding_model else excluded.embedding_model end,
                    content_hash = excluded.content_hash,
                    updated_at = now()
                """, p);

        jdbc.update("delete from job_skills where job_id = :id", new MapSqlParameterSource("id", e.jobId()));
        List<SqlParameterSource> skills = new ArrayList<>();
        List<String> distinct = e.skills() == null ? List.of() : e.skills().stream().distinct().toList();
        for (int i = 0; i < distinct.size(); i++) {
            skills.add(new MapSqlParameterSource().addValue("id", e.jobId()).addValue("skill", distinct.get(i))
                    .addValue("pos", i));
        }
        if (!skills.isEmpty()) {
            jdbc.batchUpdate("insert into job_skills (job_id, skill, position) values (:id, :skill, :pos)",
                    skills.toArray(SqlParameterSource[]::new));
        }
        jdbc.update("""
                insert into pending_match_jobs (job_id, queued_at) values (:id, clock_timestamp())
                on conflict (job_id) do update set queued_at = excluded.queued_at
                """,
                new MapSqlParameterSource("id", e.jobId()));
        return wasOpen == null || !wasOpen;
    }

    /** Marks a job closed; it disappears from search, recommendations and digests. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean close(UUID jobId, Instant closedAt) {
        int rows = jdbc.update("""
                update jobs set status = 'CLOSED', closed_at = :closedAt, updated_at = now()
                 where id = :id and status = 'OPEN'
                """, new MapSqlParameterSource().addValue("id", jobId).addValue("closedAt", ts(closedAt)));
        jdbc.update("delete from pending_match_jobs where job_id = :id", new MapSqlParameterSource("id", jobId));
        return rows == 1;
    }

    private UUID upsertCompany(String name) {
        String slug = slug(name);
        return jdbc.queryForObject("""
                insert into companies (id, slug, name) values (:id, :slug, :name)
                on conflict (slug) do update set name = excluded.name, updated_at = now()
                returning id
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("slug", slug)
                .addValue("name", name), UUID.class);
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "unknown" : s.substring(0, Math.min(200, s.length()));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
