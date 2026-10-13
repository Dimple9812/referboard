package com.referboard.referral.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.web.ApiException;
import com.referboard.referral.client.JobClient;
import com.referboard.referral.client.ProfileClient;
import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.UserSnapshot;
import com.referboard.referral.repository.JobSnapshotRepository;
import com.referboard.referral.repository.UserSnapshotRepository;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps local copies of the job and user facts that drafts are built from. Called on user requests (with the
 * user's forwarded token), so it never runs inside a DB transaction; the upserts are single statements and safe
 * under concurrent requests. If an upstream is down and we already have a copy, the stale copy is used.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    static final Duration JOB_TTL = Duration.ofHours(6);
    static final Duration USER_TTL = Duration.ofMinutes(10);
    static final int EXCERPT_CHARS = 600;
    static final int MAX_ACHIEVEMENTS = 5;
    static final int MAX_ACHIEVEMENT_CHARS = 220;
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    private final JobClient jobClient;
    private final ProfileClient profileClient;
    private final JobSnapshotRepository jobs;
    private final UserSnapshotRepository users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SnapshotService(JobClient jobClient, ProfileClient profileClient, JobSnapshotRepository jobs,
                           UserSnapshotRepository users, JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jobClient = jobClient;
        this.profileClient = profileClient;
        this.jobs = jobs;
        this.users = users;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The job, refreshed from job-service when older than {@link #JOB_TTL}. 404 if job-service doesn't know it. */
    public JobSnapshot job(UUID jobId) {
        var existing = jobs.findById(jobId);
        if (existing.isPresent() && existing.get().getFetchedAt().isAfter(clock.instant().minus(JOB_TTL))) {
            return existing.get();
        }
        JobClient.JobDetail detail;
        try {
            detail = upstream(() -> jobClient.job(jobId));
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                throw ApiException.notFound("Job", jobId);
            }
            if (existing.isPresent()) {
                log.warn("job-service unavailable; using snapshot of job {} from {}", jobId,
                        existing.get().getFetchedAt());
                return existing.get();
            }
            throw e;
        }
        if (detail == null || detail.job() == null) {
            throw ApiException.notFound("Job", jobId);
        }
        upsertJob(jobId, detail);
        return jobs.findById(jobId).orElseThrow();
    }

    /** The current user's facts, refreshed from profile-service when older than {@link #USER_TTL}. */
    public UserSnapshot user(UUID userId) {
        var existing = users.findById(userId);
        if (existing.isPresent() && existing.get().getFetchedAt().isAfter(clock.instant().minus(USER_TTL))) {
            return existing.get();
        }
        ProfileClient.Profile profile;
        try {
            profile = upstream(profileClient::me);
        } catch (ApiException e) {
            if (existing.isPresent()) {
                log.warn("profile-service unavailable; using snapshot of user {} from {}", userId,
                        existing.get().getFetchedAt());
                return existing.get();
            }
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                profile = null;
            } else {
                throw e;
            }
        }
        upsertUser(userId, profile);
        return users.findById(userId).orElseThrow();
    }

    /** Snapshot for scheduled work (no user token): whatever we stored last, or null. */
    public JobSnapshot storedJob(UUID jobId) {
        return jobs.findById(jobId).orElse(null);
    }

    public UserSnapshot storedUser(UUID userId) {
        return users.findById(userId).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------------------

    private void upsertJob(UUID jobId, JobClient.JobDetail detail) {
        JobClient.Job job = detail.job();
        List<String> skills = job.skills() == null ? List.of()
                : job.skills().stream().map(s -> s.label() != null ? s.label() : s.name())
                .filter(Objects::nonNull).distinct().toList();
        jdbc.update("""
                        insert into job_snapshots (job_id, title, company_name, location, apply_url, skills,
                                                   description_excerpt, fetched_at)
                        values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                        on conflict (job_id) do update set title = excluded.title,
                            company_name = excluded.company_name, location = excluded.location,
                            apply_url = excluded.apply_url, skills = excluded.skills,
                            description_excerpt = excluded.description_excerpt, fetched_at = excluded.fetched_at
                        """,
                jobId, truncate(job.title(), 300), truncate(job.companyName(), 200), truncate(job.location(), 300),
                truncate(job.applyUrl(), 2000), json(skills), excerpt(detail.descriptionHtml()),
                Timestamp.from(clock.instant()));
    }

    private void upsertUser(UUID userId, ProfileClient.Profile p) {
        boolean has = p != null && p.exists();
        List<String> skills = has && p.skills() != null
                ? p.skills().stream().map(s -> s.displayName() != null ? s.displayName() : s.name())
                .filter(Objects::nonNull).distinct().toList()
                : List.of();
        jdbc.update("""
                        insert into user_snapshots (user_id, full_name, headline, current_title, current_company,
                                                    experience_years, college, past_companies, skills, achievements,
                                                    fetched_at)
                        values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
                        on conflict (user_id) do update set full_name = excluded.full_name,
                            headline = excluded.headline, current_title = excluded.current_title,
                            current_company = excluded.current_company, experience_years = excluded.experience_years,
                            college = excluded.college, past_companies = excluded.past_companies,
                            skills = excluded.skills, achievements = excluded.achievements,
                            fetched_at = excluded.fetched_at
                        """,
                userId,
                has ? truncate(p.fullName(), 200) : null,
                has ? truncate(p.headline(), 300) : null,
                has ? truncate(p.currentTitle(), 200) : null,
                has ? truncate(p.currentCompany(), 200) : null,
                has ? p.totalExperienceYears() : null,
                has ? truncate(p.college(), 300) : null,
                json(has ? pastCompanies(p) : List.of()),
                json(skills),
                json(has ? achievements(p) : List.of()),
                Timestamp.from(clock.instant()));
    }

    /** Employers other than the current one, most recent first. */
    static List<String> pastCompanies(ProfileClient.Profile p) {
        if (p.experiences() == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (ProfileClient.Experience e : p.experiences()) {
            if (e.company() != null && !e.company().isBlank()
                    && !e.company().equalsIgnoreCase(Objects.requireNonNullElse(p.currentCompany(), ""))) {
                out.add(e.company().trim());
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Resume bullets with a number in them ("Cut p99 latency by 40%"), copied verbatim. Drafts may quote these and
     * nothing else quantitative, so a message can never claim an achievement the resume doesn't state.
     */
    static List<String> achievements(ProfileClient.Profile p) {
        if (p.experiences() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (ProfileClient.Experience e : p.experiences()) {
            if (e.bullets() == null) {
                continue;
            }
            for (String b : e.bullets()) {
                if (b != null && HAS_DIGIT.matcher(b).find() && b.length() <= MAX_ACHIEVEMENT_CHARS) {
                    out.add(b.trim());
                    if (out.size() == MAX_ACHIEVEMENTS) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /** Connection failures/timeouts surface as FeignException, not through the ErrorDecoder: make them a 502. */
    private static <T> T upstream(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (feign.FeignException e) {
            log.warn("Upstream call failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
                    "A dependent service is unavailable. Please try again shortly.");
        }
    }

    static String excerpt(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = Jsoup.parse(html).text().replaceAll("\\s+", " ").trim();
        if (text.length() <= EXCERPT_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf(' ', EXCERPT_CHARS);
        return text.substring(0, cut > EXCERPT_CHARS / 2 ? cut : EXCERPT_CHARS) + "…";
    }

    private String json(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
