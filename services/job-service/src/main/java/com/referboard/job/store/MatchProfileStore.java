package com.referboard.job.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.events.ProfileUpdatedEvent;
import com.referboard.job.matching.MatchScorer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** job-service's own copy of each user's ProfileUpdated (it never reads profile-service's tables). */
@Repository
public class MatchProfileStore {

    public record MatchProfile(UUID userId, long version, List<String> skills, BigDecimal experienceYears,
                               MatchScorer.Preferences preferences, String embeddingLiteral, String embeddingModel) {

        public MatchScorer.UserFacts facts() {
            return new MatchScorer.UserFacts(skills, experienceYears, preferences);
        }

        public boolean hasEmbedding() {
            return embeddingLiteral != null;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MatchProfileStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @return false if we already hold this or a newer profile version (stale/duplicate event) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean upsert(ProfileUpdatedEvent e) {
        ProfileUpdatedEvent.Preferences p = e.preferences();
        MatchScorer.Preferences prefs = p == null ? MatchScorer.Preferences.NONE : new MatchScorer.Preferences(
                p.roles() == null ? List.of() : p.roles(), p.locations() == null ? List.of() : p.locations(),
                p.remoteOnly(), p.openToRemote(), p.minSalaryInr(), p.experienceLevel());
        int rows = jdbc.update("""
                insert into user_match_profiles (user_id, profile_version, headline, skills, experience_years,
                                                 preferences, embedding, embedding_model, updated_at)
                values (:uid, :version, :headline, cast(:skills as jsonb), :years, cast(:prefs as jsonb),
                        cast(:embedding as public.vector), :model, now())
                on conflict (user_id) do update set
                    profile_version = excluded.profile_version, headline = excluded.headline,
                    skills = excluded.skills, experience_years = excluded.experience_years,
                    preferences = excluded.preferences, embedding = excluded.embedding,
                    embedding_model = excluded.embedding_model, updated_at = now()
                 where user_match_profiles.profile_version < excluded.profile_version
                """, new MapSqlParameterSource()
                .addValue("uid", e.userId())
                .addValue("version", e.profileVersion())
                .addValue("headline", e.headline())
                .addValue("skills", json(e.skills() == null ? List.of() : e.skills()))
                .addValue("years", e.totalExperienceYears())
                .addValue("prefs", json(prefs))
                .addValue("embedding", Vectors.literal(e.embedding()), Types.VARCHAR)
                .addValue("model", e.embedding() == null ? null : e.embeddingModel()));
        return rows == 1;
    }

    private static final String SELECT = """
            select user_id, profile_version, skills::text as skills, experience_years, preferences::text as prefs,
                   embedding::text as embedding, embedding_model
              from user_match_profiles
            """;

    public Optional<MatchProfile> find(UUID userId) {
        return findAll(List.of(userId)).stream().findFirst();
    }

    public List<MatchProfile> findAll(java.util.Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(SELECT + " where user_id in (:uids)", new MapSqlParameterSource("uids", userIds),
                (rs, i) -> new MatchProfile(
                (UUID) rs.getObject("user_id"), rs.getLong("profile_version"),
                read(rs.getString("skills"), new TypeReference<List<String>>() { }),
                rs.getBigDecimal("experience_years"),
                read(rs.getString("prefs"), new TypeReference<MatchScorer.Preferences>() { }),
                rs.getString("embedding"), rs.getString("embedding_model")));
    }

    public List<UUID> userIdsAfter(UUID after, int limit) {
        return jdbc.queryForList("""
                select user_id from user_match_profiles where (cast(:after as uuid) is null or user_id > :after)
                 order by user_id limit :limit
                """, new MapSqlParameterSource().addValue("after", after, Types.OTHER).addValue("limit", limit),
                UUID.class);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
