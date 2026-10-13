package com.referboard.job.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.events.DailyMatchDigestEvent;
import com.referboard.job.config.JobsProperties;
import com.referboard.job.store.MatchStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Once a day (08:00 IST by default) publishes each user's top new matches (≥ min score, max 10) for
 * notification-service to email. A match is included in at most one digest.
 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private final MatchStore matches;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final ObjectMapper mapper;
    private final JobsProperties.Digest config;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DigestService(MatchStore matches, EventPublisher events, EventJson eventJson, ObjectMapper mapper,
                         JobsProperties props, PlatformTransactionManager txManager, Clock clock) {
        this.matches = matches;
        this.events = events;
        this.eventJson = eventJson;
        this.mapper = mapper;
        this.config = props.digest();
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${referboard.jobs.digest.cron:0 0 8 * * *}", zone = "${referboard.jobs.digest.zone:Asia/Kolkata}")
    public void scheduled() {
        run();
    }

    /** @return number of digests published */
    public int run() {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofHours(24));
        LocalDate day = LocalDate.ofInstant(now, ZoneId.of(config.zone()));
        int published = 0;
        for (UUID userId : matches.usersWithNewMatches(since, config.minScore())) {
            List<MatchStore.DigestRow> rows = matches.digestFor(userId, since, config.minScore(), config.maxJobs());
            if (rows.isEmpty()) {
                continue;
            }
            List<DailyMatchDigestEvent.MatchSummary> summaries = rows.stream()
                    .map(r -> new DailyMatchDigestEvent.MatchSummary(r.jobId(), r.title(), r.companyName(), r.location(),
                            r.remote(), r.score(), readSkills(r.matchedSkillsJson())))
                    .toList();
            tx.executeWithoutResult(s -> {
                events.publish(new DailyMatchDigestEvent(eventJson.newMetadata("DailyMatchDigest"), userId, day,
                        summaries));
                matches.markDigested(userId, rows.stream().map(MatchStore.DigestRow::jobId).toList(), now);
            });
            published++;
        }
        log.info("Daily digest: published for {} users", published);
        return published;
    }

    private List<String> readSkills(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
