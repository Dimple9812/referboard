package com.referboard.job.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.skills.Cities;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the catalogue. Full-text search (weighted tsvector: title > company > description) combined with
 * trigram similarity on titles for typos and partial words; pgvector for semantic similarity.
 */
@Repository
public class SearchRepository {

    static final String CARD_COLUMNS = """
            j.id, j.title, j.company_name, j.location, j.remote, j.seniority, j.employment_type, j.listed_at,
            j.salary_min, j.salary_max, j.salary_currency, j.ghost_job_suspected, j.apply_url, j.source,
            (select coalesce(json_agg(s.skill order by s.position), '[]'::json)::text
               from job_skills s where s.job_id = j.id) as skills
            """;

    public record Page(List<JobCardRow> items, long total) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RowMapper<JobCardRow> cardMapper;

    public SearchRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.cardMapper = (rs, i) -> new JobCardRow(
                (UUID) rs.getObject("id"), rs.getString("title"), rs.getString("company_name"),
                rs.getString("location"), rs.getBoolean("remote"), rs.getString("seniority"),
                rs.getString("employment_type"), rs.getTimestamp("listed_at").toInstant(),
                (Long) rs.getObject("salary_min"), (Long) rs.getObject("salary_max"), rs.getString("salary_currency"),
                readSkills(rs.getString("skills")), rs.getBoolean("ghost_job_suspected"), rs.getString("apply_url"),
                rs.getString("source"));
    }

    public RowMapper<JobCardRow> cardMapper() {
        return cardMapper;
    }

    /** @param userId only needed for sort=MATCH (joins precomputed matches) */
    public Page search(SearchCriteria c, UUID userId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = whereClause(c, p);
        String from = " from jobs j";
        String orderBy;
        String select = "select " + CARD_COLUMNS;
        switch (c.effectiveSort()) {
            case RELEVANCE -> {
                select += ", ts_rank_cd(j.search_tsv, websearch_to_tsquery('english', :q)) "
                        + "+ similarity(j.title, :q) as rank";
                orderBy = " order by rank desc, j.listed_at desc, j.id";
            }
            case MATCH -> {
                from += " left join job_matches m on m.job_id = j.id and m.user_id = :uid";
                p.addValue("uid", userId);
                orderBy = " order by m.score desc nulls last, j.listed_at desc, j.id";
            }
            default -> orderBy = " order by j.listed_at desc, j.id";
        }
        p.addValue("limit", c.size()).addValue("offset", (long) c.page() * c.size());
        List<JobCardRow> items = jdbc.query(select + from + where + orderBy + " limit :limit offset :offset", p,
                cardMapper);
        Long total = jdbc.queryForObject("select count(*) from jobs j" + where, p, Long.class);
        return new Page(items, total == null ? 0 : total);
    }

    private String whereClause(SearchCriteria c, MapSqlParameterSource p) {
        List<String> clauses = new ArrayList<>();
        clauses.add("j.status = 'OPEN'");
        if (notBlank(c.q())) {
            p.addValue("q", c.q().trim());
            clauses.add("(j.search_tsv @@ websearch_to_tsquery('english', :q) or j.title % :q)");
        }
        if (notBlank(c.role())) {
            // Every word of the role must appear in the title, exactly or fuzzily. Whole-phrase trigram similarity
            // can't tell a typo ("bakend engineer" vs "backend engineer": 0.74) from a different role ("ios engineer"
            // vs "frontend engineer": 0.69); per word the gap is wide ("bakend"→"backend" 0.50, "backend"→"frontend" 0.25).
            String role = c.role().trim().toLowerCase(Locale.ROOT);
            List<String> words = java.util.Arrays.stream(role.split("[^a-z0-9+#.]+")).filter(w -> w.length() >= 2).toList();
            List<String> all = new ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                p.addValue("roleLike" + i, "%" + escapeLike(words.get(i)) + "%");
                p.addValue("roleWord" + i, words.get(i));
                all.add("(j.normalized_title like :roleLike" + i
                        + " or word_similarity(:roleWord" + i + ", j.normalized_title) >= 0.45)");
            }
            clauses.add(all.isEmpty() ? "true" : "(" + String.join(" and ", all) + ")");
        }
        if (notBlank(c.location())) {
            List<String> ors = new ArrayList<>();
            List<String> spellings = Cities.spellings(c.location());
            for (int i = 0; i < spellings.size(); i++) {
                p.addValue("loc" + i, "%" + escapeLike(spellings.get(i)) + "%");
                ors.add("lower(j.location) like :loc" + i);
            }
            clauses.add("(" + String.join(" or ", ors) + ")");
        }
        if (c.remote() != null) {
            p.addValue("remote", c.remote());
            clauses.add("j.remote = :remote");
        }
        if (c.postedWithinDays() != null) {
            p.addValue("since", Timestamp.from(clock.instant().minus(Duration.ofDays(c.postedWithinDays()))));
            clauses.add("j.listed_at >= :since");
        }
        if (notBlank(c.company())) {
            p.addValue("company", "%" + escapeLike(c.company().trim().toLowerCase(Locale.ROOT)) + "%");
            clauses.add("lower(j.company_name) like :company");
        }
        if (c.seniorities() != null && !c.seniorities().isEmpty()) {
            p.addValue("seniorities", c.seniorities());
            clauses.add("j.seniority in (:seniorities)");
        }
        if (c.skills() != null && !c.skills().isEmpty()) {
            p.addValue("skills", c.skills());
            clauses.add("exists (select 1 from job_skills s where s.job_id = j.id and s.skill in (:skills))");
        }
        if (c.hideGhostJobs()) {
            clauses.add("not j.ghost_job_suspected");
        }
        return " where " + String.join(" and ", clauses);
    }

    /**
     * Cosine similarity of each job to the user's profile embedding, computed in PostgreSQL. Null where either
     * side has no vector or they come from different embedding models (not comparable).
     */
    public Map<UUID, Double> similarities(UUID userId, Collection<UUID> jobIds) {
        Map<UUID, Double> out = new HashMap<>();
        if (jobIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                select j.id,
                       case when u.embedding is not null and j.embedding is not null
                                 and u.embedding_model = j.embedding_model
                            then 1 - (j.embedding <=> u.embedding) end as cos
                  from jobs j cross join user_match_profiles u
                 where u.user_id = :uid and j.id in (:ids)
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("ids", jobIds), rs -> {
            double cos = rs.getDouble("cos");
            out.put((UUID) rs.getObject("id"), rs.wasNull() ? null : cos);
        });
        return out;
    }

    public List<JobCardRow> cards(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select " + CARD_COLUMNS + " from jobs j where j.id in (:ids)",
                new MapSqlParameterSource("ids", ids), cardMapper);
    }

    public record JobDetailRow(JobCardRow card, String descriptionHtml, String department, String country,
                               String status, java.time.Instant closedAt, java.time.Instant firstSeenAt,
                               java.time.Instant lastSeenAt) {
    }

    public java.util.Optional<JobDetailRow> detail(UUID id) {
        return jdbc.query("select " + CARD_COLUMNS + """
                        , j.description_html, j.department, j.country, j.status, j.closed_at, j.first_seen_at,
                          j.last_seen_at from jobs j where j.id = :id
                        """, new MapSqlParameterSource("id", id),
                (rs, i) -> new JobDetailRow(cardMapper.mapRow(rs, i), rs.getString("description_html"),
                        rs.getString("department"), rs.getString("country"), rs.getString("status"),
                        toInstant(rs.getTimestamp("closed_at")), toInstant(rs.getTimestamp("first_seen_at")),
                        toInstant(rs.getTimestamp("last_seen_at"))))
                .stream().findFirst();
    }

    /**
     * Candidate open jobs for a user: nearest neighbours by embedding (HNSW index, same model) plus jobs sharing at
     * least one skill. The vector is passed as a literal so the planner can use the index.
     */
    public List<JobCardRow> candidatesFor(String embeddingLiteral, String embeddingModel, List<String> skills,
                                          int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("limit", limit)
                .addValue("vec", embeddingLiteral, Types.VARCHAR).addValue("model", embeddingModel, Types.VARCHAR)
                .addValue("skills", skills.isEmpty() ? List.of("__none__") : skills);
        return jdbc.query("with sem as (select j.id from jobs j where j.status = 'OPEN' and cast(:vec as text) is not null"
                + " and j.embedding_model = :model order by j.embedding <=> cast(:vec as public.vector) limit :limit),"
                + " sk as (select s.job_id as id from job_skills s join jobs j on j.id = s.job_id"
                + " where j.status = 'OPEN' and s.skill in (:skills)"
                + " group by s.job_id order by count(*) desc, max(j.listed_at) desc limit :limit),"
                + " cand as (select id from sem union select id from sk)"
                + " select " + CARD_COLUMNS + " from jobs j join cand on cand.id = j.id", p, cardMapper);
    }

    private List<String> readSkills(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.time.Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
