package com.referboard.job.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.job.matching.MatchScorer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Precomputed matches: recommendations, sort-by-match, daily digest. */
@Repository
public class MatchStore {

    public record Scored(UUID jobId, MatchScorer.Breakdown breakdown) {
    }

    public record DigestRow(UUID jobId, String title, String companyName, String location, boolean remote, int score,
                            String matchedSkillsJson) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MatchStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Replaces all of a user's matches (after their profile changed). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceForUser(UUID userId, List<Scored> matches, Instant now) {
        jdbc.update("delete from job_matches where user_id = :uid", new MapSqlParameterSource("uid", userId));
        upsert(userId, matches, now);
    }

    /** Adds/updates matches for one user; rows already digested keep their digested_at. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsert(UUID userId, List<Scored> matches, Instant now) {
        if (matches.isEmpty()) {
            return;
        }
        SqlParameterSource[] rows = matches.stream().map(m -> new MapSqlParameterSource()
                .addValue("uid", userId).addValue("jid", m.jobId())
                .addValue("score", m.breakdown().matchPercent())
                .addValue("sem", m.breakdown().semantic())
                .addValue("skills", m.breakdown().skills())
                .addValue("prefs", m.breakdown().preferences())
                .addValue("matched", json(m.breakdown().matchedSkills()))
                .addValue("missing", json(m.breakdown().missingSkills()))
                .addValue("now", Timestamp.from(now))).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into job_matches (user_id, job_id, score, semantic_score, skill_score, preference_score,
                                         matched_skills, missing_skills, computed_at)
                values (:uid, :jid, :score, :sem, :skills, :prefs, cast(:matched as jsonb), cast(:missing as jsonb), :now)
                on conflict (user_id, job_id) do update set
                    score = excluded.score, semantic_score = excluded.semantic_score,
                    skill_score = excluded.skill_score, preference_score = excluded.preference_score,
                    matched_skills = excluded.matched_skills, missing_skills = excluded.missing_skills,
                    computed_at = case when job_matches.score = excluded.score then job_matches.computed_at
                                       else excluded.computed_at end
                """, rows);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(UUID userId, List<UUID> jobIds) {
        if (!jobIds.isEmpty()) {
            jdbc.update("delete from job_matches where user_id = :uid and job_id in (:ids)",
                    new MapSqlParameterSource().addValue("uid", userId).addValue("ids", jobIds));
        }
    }

    /** Trims a user's stored matches to the best {@code keep}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void trim(UUID userId, int keep) {
        jdbc.update("""
                delete from job_matches where user_id = :uid and job_id not in (
                    select job_id from job_matches where user_id = :uid order by score desc, computed_at desc limit :keep)
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("keep", keep));
    }

    public List<UUID> topJobIds(UUID userId, int offset, int limit) {
        return jdbc.queryForList("""
                select m.job_id from job_matches m join jobs j on j.id = m.job_id
                 where m.user_id = :uid and j.status = 'OPEN'
                 order by m.score desc, j.listed_at desc, m.job_id
                 limit :limit offset :offset
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("limit", limit)
                .addValue("offset", offset), UUID.class);
    }

    public long countOpen(UUID userId) {
        Long n = jdbc.queryForObject("""
                select count(*) from job_matches m join jobs j on j.id = m.job_id
                 where m.user_id = :uid and j.status = 'OPEN'
                """, new MapSqlParameterSource("uid", userId), Long.class);
        return n == null ? 0 : n;
    }

    /** Users with fresh, not-yet-digested matches above {@code minScore}. */
    public List<UUID> usersWithNewMatches(Instant since, int minScore) {
        return jdbc.queryForList("""
                select distinct m.user_id from job_matches m join jobs j on j.id = m.job_id
                 where m.digested_at is null and m.computed_at >= :since and m.score >= :min and j.status = 'OPEN'
                """, new MapSqlParameterSource().addValue("since", Timestamp.from(since)).addValue("min", minScore),
                UUID.class);
    }

    public List<DigestRow> digestFor(UUID userId, Instant since, int minScore, int limit) {
        return jdbc.query("""
                select j.id, j.title, j.company_name, j.location, j.remote, m.score, m.matched_skills::text as matched
                  from job_matches m join jobs j on j.id = m.job_id
                 where m.user_id = :uid and m.digested_at is null and m.computed_at >= :since and m.score >= :min
                   and j.status = 'OPEN'
                 order by m.score desc, j.listed_at desc limit :limit
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("since", Timestamp.from(since))
                .addValue("min", minScore).addValue("limit", limit),
                (rs, i) -> new DigestRow((UUID) rs.getObject("id"), rs.getString("title"), rs.getString("company_name"),
                        rs.getString("location"), rs.getBoolean("remote"), rs.getInt("score"), rs.getString("matched")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markDigested(UUID userId, List<UUID> jobIds, Instant now) {
        if (!jobIds.isEmpty()) {
            jdbc.update("update job_matches set digested_at = :now where user_id = :uid and job_id in (:ids)",
                    new MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("uid", userId)
                            .addValue("ids", jobIds));
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
