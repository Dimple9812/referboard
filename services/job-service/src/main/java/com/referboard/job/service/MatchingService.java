package com.referboard.job.service;

import com.referboard.job.config.JobsProperties;
import com.referboard.job.matching.MatchScorer;
import com.referboard.job.store.JobCardRow;
import com.referboard.job.store.MatchProfileStore;
import com.referboard.job.store.MatchProfileStore.MatchProfile;
import com.referboard.job.store.MatchStore;
import com.referboard.job.store.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Two ways a match is computed, both with {@link MatchScorer}:
 * <ul>
 *   <li><b>Live</b> for whatever the user is looking at (a search page, a job detail): always reflects the current
 *       profile and job.</li>
 *   <li><b>Precomputed</b> into {@code job_matches} for recommendations, sort-by-match and the daily digest:
 *       rebuilt for a user when their profile changes, and extended in the background as new jobs arrive.</li>
 * </ul>
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    private static final int PENDING_BATCH = 200;
    private static final int USER_PAGE = 500;

    private final MatchProfileStore profiles;
    private final MatchStore matches;
    private final SearchRepository search;
    private final NamedParameterJdbcTemplate jdbc;
    private final JobsProperties.Match config;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MatchingService(MatchProfileStore profiles, MatchStore matches, SearchRepository search,
                           NamedParameterJdbcTemplate jdbc, JobsProperties props, PlatformTransactionManager txManager,
                           Clock clock) {
        this.profiles = profiles;
        this.matches = matches;
        this.search = search;
        this.jdbc = jdbc;
        this.config = props.match();
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Live scores for the given jobs; empty if the user has no profile yet. */
    public Map<UUID, MatchScorer.Breakdown> live(UUID userId, List<JobCardRow> jobs) {
        if (userId == null || jobs.isEmpty()) {
            return Map.of();
        }
        return profiles.find(userId).map(profile -> {
            Map<UUID, Double> cos = search.similarities(userId, jobs.stream().map(JobCardRow::id).toList());
            Map<UUID, MatchScorer.Breakdown> out = new LinkedHashMap<>();
            for (JobCardRow job : jobs) {
                out.put(job.id(), MatchScorer.score(profile.facts(), job.facts(cos.get(job.id()))));
            }
            return out;
        }).orElse(Map.of());
    }

    /** Rebuilds a user's stored matches from scratch. Runs inside the ProfileUpdated consumer's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recomputeForUser(UUID userId) {
        MatchProfile profile = profiles.find(userId).orElse(null);
        if (profile == null) {
            return 0;
        }
        List<JobCardRow> candidates = search.candidatesFor(profile.embeddingLiteral(), profile.embeddingModel(),
                profile.skills(), config.candidateLimit());
        Map<UUID, Double> cos = search.similarities(userId, candidates.stream().map(JobCardRow::id).toList());
        List<MatchStore.Scored> kept = candidates.stream()
                .map(job -> new MatchStore.Scored(job.id(), MatchScorer.score(profile.facts(), job.facts(cos.get(job.id())))))
                .filter(s -> s.breakdown().matchPercent() >= config.threshold())
                .sorted(Comparator.comparingInt((MatchStore.Scored s) -> s.breakdown().matchPercent()).reversed())
                .limit(config.perUserLimit())
                .toList();
        matches.replaceForUser(userId, kept, clock.instant());
        log.debug("Recomputed matches for user {}: {} candidates, {} kept", userId, candidates.size(), kept.size());
        return kept.size();
    }

    /**
     * Scores newly ingested/changed jobs against every user, in batches. Pending rows are only removed once
     * processed, so a crash just means the batch is scored again next time (scoring is idempotent).
     */
    @Scheduled(fixedDelayString = "${referboard.jobs.match.refresh-interval-ms:60000}",
            initialDelayString = "${referboard.jobs.match.refresh-interval-ms:60000}")
    public void refreshPending() {
        int processed;
        do {
            processed = processPendingBatch();
        } while (processed == PENDING_BATCH);
    }

    int processPendingBatch() {
        java.sql.Timestamp batchStart = jdbc.queryForObject("select clock_timestamp()", new MapSqlParameterSource(),
                java.sql.Timestamp.class);
        List<UUID> jobIds = jdbc.queryForList(
                "select job_id from pending_match_jobs order by queued_at limit :n",
                new MapSqlParameterSource("n", PENDING_BATCH), UUID.class);
        if (jobIds.isEmpty()) {
            return 0;
        }
        Map<UUID, JobCardRow> jobs = search.cards(jobIds).stream()
                .collect(Collectors.toMap(JobCardRow::id, Function.identity()));
        UUID after = null;
        int scored = 0;
        while (true) {
            List<UUID> userIds = profiles.userIdsAfter(after, USER_PAGE);
            if (userIds.isEmpty()) {
                break;
            }
            Map<UUID, MatchProfile> page = profiles.findAll(userIds).stream()
                    .collect(Collectors.toMap(MatchProfile::userId, Function.identity()));
            Map<UUID, Map<UUID, Double>> cos = similarities(userIds, jobs.keySet());
            for (UUID uid : userIds) {
                MatchProfile profile = page.get(uid);
                if (profile == null) {
                    continue;
                }
                List<MatchStore.Scored> keep = new ArrayList<>();
                List<UUID> drop = new ArrayList<>();
                for (JobCardRow job : jobs.values()) {
                    MatchScorer.Breakdown b = MatchScorer.score(profile.facts(),
                            job.facts(cos.getOrDefault(uid, Map.of()).get(job.id())));
                    if (b.matchPercent() >= config.threshold()) {
                        keep.add(new MatchStore.Scored(job.id(), b));
                    } else {
                        drop.add(job.id());
                    }
                }
                if (!keep.isEmpty() || !drop.isEmpty()) {
                    tx.executeWithoutResult(s -> {
                        matches.upsert(uid, keep, clock.instant());
                        matches.delete(uid, drop);
                        if (!keep.isEmpty()) {
                            matches.trim(uid, config.perUserLimit());
                        }
                    });
                    scored += keep.size();
                }
            }
            after = userIds.getLast();
        }
        // Only rows queued before this batch started: a job that changed meanwhile stays queued for the next pass.
        jdbc.update("delete from pending_match_jobs where job_id in (:ids) and queued_at <= :start",
                new MapSqlParameterSource().addValue("ids", jobIds).addValue("start", batchStart));
        log.info("Scored {} new/changed jobs against all profiles: {} matches stored", jobIds.size(), scored);
        return jobIds.size();
    }

    /** userId → (jobId → cosine) for a page of users × a batch of jobs, in one query. */
    private Map<UUID, Map<UUID, Double>> similarities(List<UUID> userIds, java.util.Collection<UUID> jobIds) {
        Map<UUID, Map<UUID, Double>> out = new HashMap<>();
        if (jobIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                select u.user_id, j.id, 1 - (j.embedding <=> u.embedding) as cos
                  from user_match_profiles u join jobs j
                    on j.embedding is not null and u.embedding is not null and j.embedding_model = u.embedding_model
                 where u.user_id in (:uids) and j.id in (:jids)
                """, new MapSqlParameterSource().addValue("uids", userIds).addValue("jids", jobIds), rs -> {
            out.computeIfAbsent((UUID) rs.getObject("user_id"), k -> new HashMap<>())
                    .put((UUID) rs.getObject("id"), rs.getDouble("cos"));
        });
        return out;
    }
}
