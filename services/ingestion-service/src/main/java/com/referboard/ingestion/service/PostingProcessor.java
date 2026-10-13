package com.referboard.ingestion.service;

import com.referboard.ai.EmbeddingClient;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.events.JobClosedEvent;
import com.referboard.events.JobUpsertedEvent;
import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.domain.IngestedPosting;
import com.referboard.ingestion.normalize.NormalizedJob;
import com.referboard.ingestion.repository.IngestedPostingRepository;
import com.referboard.ingestion.source.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies one source's normalised postings to the store and emits events. Idempotent: re-processing the same
 * postings publishes nothing (content hash unchanged), so a crashed run can simply be re-run.
 *
 * <p>Rules:
 * <ul>
 *   <li>New de-dup key → insert, embed, publish JobUpserted.</li>
 *   <li>Same key, content changed or previously closed → update, re-embed, publish.</li>
 *   <li>Same key, unchanged → only {@code last_seen_at} moves (no event), unless the ghost flag or repost count
 *       changed, which publishes with {@code embedding = null} (consumers keep their stored vector when the content
 *       hash is unchanged).</li>
 *   <li>A lower-priority source (Adzuna) never overwrites, touches or reopens a posting owned by an ATS board:
 *       the company's own board is the source of truth for whether a job is still open.</li>
 * </ul>
 */
@Service
public class PostingProcessor {

    private static final Logger log = LoggerFactory.getLogger(PostingProcessor.class);
    private static final Duration REPOST_THRESHOLD = Duration.ofDays(1);

    public record Stats(int created, int updated, int unchanged, int shadowed) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0);

        public Stats plus(Stats o) {
            return new Stats(created + o.created, updated + o.updated, unchanged + o.unchanged, shadowed + o.shadowed);
        }
    }

    private enum Action { CREATE, CONTENT_CHANGED, FLAGS_CHANGED, TOUCH, SHADOWED }

    private record Decision(NormalizedJob job, IngestedPosting posting, Action action) {
    }

    private final IngestedPostingRepository postings;
    private final EmbeddingClient embeddings;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final TransactionTemplate tx;
    private final Duration ghostAfter;

    public PostingProcessor(IngestedPostingRepository postings, EmbeddingClient embeddings, EventPublisher events,
                            EventJson eventJson, PlatformTransactionManager txManager, IngestionProperties props) {
        this.postings = postings;
        this.embeddings = embeddings;
        this.events = events;
        this.eventJson = eventJson;
        this.tx = new TransactionTemplate(txManager);
        this.ghostAfter = props.ghostAfter();
    }

    public Stats process(List<NormalizedJob> incoming, Instant now) {
        return process(incoming, now, false);
    }

    /**
     * @param forceRepublish re-publish (and re-embed) postings even when unchanged — used to rebuild a downstream
     *                       consumer (e.g. a fresh job-service database) after Kafka retention has expired
     */
    public Stats process(List<NormalizedJob> incoming, Instant now, boolean forceRepublish) {
        if (incoming.isEmpty()) {
            return Stats.EMPTY;
        }
        // The same job can appear twice in one batch (e.g. listed under two departments): keep the best copy.
        Map<UUID, NormalizedJob> unique = incoming.stream().collect(Collectors.toMap(NormalizedJob::jobId,
                Function.identity(), PostingProcessor::better, LinkedHashMap::new));

        Map<UUID, IngestedPosting> existing = postings.findByJobIdIn(unique.keySet()).stream()
                .collect(Collectors.toMap(IngestedPosting::getJobId, Function.identity()));

        List<Decision> decisions = new ArrayList<>();
        for (NormalizedJob job : unique.values()) {
            Decision d = decide(job, existing.get(job.jobId()), now);
            if (forceRepublish && (d.action() == Action.TOUCH || d.action() == Action.FLAGS_CHANGED)) {
                d = new Decision(d.job(), d.posting(), Action.CONTENT_CHANGED);
            }
            decisions.add(d);
        }

        // Embed outside the transaction: it may be a network call.
        List<Decision> toEmbed = decisions.stream()
                .filter(d -> d.action() == Action.CREATE || d.action() == Action.CONTENT_CHANGED).toList();
        Map<UUID, float[]> vectors = embed(toEmbed);

        tx.executeWithoutResult(status -> {
            for (Decision d : decisions) {
                switch (d.action()) {
                    case SHADOWED -> {
                    }
                    case TOUCH -> postings.save(d.posting());
                    default -> {
                        IngestedPosting saved = postings.save(d.posting());
                        float[] vector = vectors.get(d.job().jobId());
                        events.publish(toEvent(d.job(), saved, vector, vector == null ? null : embeddings.model()));
                    }
                }
            }
        });

        Map<Action, Long> counts = decisions.stream().collect(Collectors.groupingBy(Decision::action, Collectors.counting()));
        return new Stats(
                counts.getOrDefault(Action.CREATE, 0L).intValue(),
                (int) (counts.getOrDefault(Action.CONTENT_CHANGED, 0L) + counts.getOrDefault(Action.FLAGS_CHANGED, 0L)),
                counts.getOrDefault(Action.TOUCH, 0L).intValue(),
                counts.getOrDefault(Action.SHADOWED, 0L).intValue());
    }

    /** Closes postings whose (healthy) source stopped listing them {@code closeAfter} ago. Returns count closed. */
    public int closeStale(Instant now, Duration closeAfter) {
        int total = 0;
        while (true) {
            Integer closed = tx.execute(status -> {
                List<IngestedPosting> stale = postings.findStale(now.minus(closeAfter), 500);
                for (IngestedPosting p : stale) {
                    p.setClosedAt(now);
                    events.publish(new JobClosedEvent(eventJson.newMetadata("JobClosed"), p.getJobId(), now,
                            JobClosedEvent.REASON_UNSEEN_7_DAYS));
                }
                postings.saveAll(stale);
                return stale.size();
            });
            total += closed == null ? 0 : closed;
            if (closed == null || closed < 500) {
                return total;
            }
        }
    }

    private Decision decide(NormalizedJob job, IngestedPosting p, Instant now) {
        if (p == null) {
            IngestedPosting created = IngestedPosting.firstSeen(job.jobId(), job.dedupKey(), now);
            apply(created, job);
            created.setPostedAt(job.postedAt());
            return new Decision(job, created, Action.CREATE);
        }
        SourceType owner = SourceType.valueOf(p.getSource().toUpperCase(Locale.ROOT));
        if (owner.priority() > job.sourceType().priority()) {
            return new Decision(job, p, Action.SHADOWED);
        }

        boolean reopened = !p.isOpen();
        boolean contentChanged = !p.getContentHash().equals(job.contentHash()) || reopened;
        boolean reposted = job.postedAt() != null && p.getPostedAt() != null
                && job.postedAt().isAfter(p.getPostedAt().plus(REPOST_THRESHOLD));
        boolean ghost = !p.getFirstSeenAt().plus(ghostAfter).isAfter(now);
        boolean flagsChanged = ghost != p.isGhostJobSuspected() || reposted;

        p.setLastSeenAt(now);
        p.setClosedAt(null);
        p.setGhostJobSuspected(ghost);
        if (reposted) {
            p.setRepostCount(p.getRepostCount() + 1);
        }
        if (job.postedAt() != null) {
            p.setPostedAt(job.postedAt());
        }
        apply(p, job);
        if (reopened) {
            log.debug("Job {} reappeared after being closed; reopening", job.jobId());
        }
        return new Decision(job, p, contentChanged ? Action.CONTENT_CHANGED : flagsChanged ? Action.FLAGS_CHANGED : Action.TOUCH);
    }

    private static void apply(IngestedPosting p, NormalizedJob job) {
        p.setSource(job.sourceType().name().toLowerCase(Locale.ROOT));
        p.setSourceKey(job.sourceKey());
        p.setSourceJobId(job.sourceJobId());
        p.setCompanyName(job.companyName());
        p.setTitle(job.title());
        p.setLocation(job.location());
        p.setContentHash(job.contentHash());
    }

    private Map<UUID, float[]> embed(List<Decision> decisions) {
        Map<UUID, float[]> out = new HashMap<>();
        if (decisions.isEmpty()) {
            return out;
        }
        try {
            List<float[]> vectors = embeddings.embedAll(decisions.stream().map(d -> embeddingText(d.job())).toList());
            for (int i = 0; i < decisions.size(); i++) {
                out.put(decisions.get(i).job().jobId(), vectors.get(i));
                decisions.get(i).posting().setEmbeddingModel(embeddings.model());
            }
        } catch (Exception e) {
            log.warn("Embedding {} jobs failed; publishing without vectors (skills-only matching): {}",
                    decisions.size(), e.getMessage());
        }
        return out;
    }

    static String embeddingText(NormalizedJob job) {
        String description = job.descriptionText() == null ? "" : job.descriptionText();
        return job.title() + "\n" + job.companyName() + " · " + (job.location() == null ? "" : job.location())
                + (job.remote() ? " · Remote" : "") + " · " + job.seniority() + "\n"
                + "Skills: " + String.join(", ", job.skills()) + "\n"
                + description.substring(0, Math.min(description.length(), 2500));
    }

    private JobUpsertedEvent toEvent(NormalizedJob j, IngestedPosting p, float[] vector, String model) {
        return new JobUpsertedEvent(eventJson.newMetadata("JobUpserted"), j.jobId(), j.dedupKey(),
                j.sourceType().name().toLowerCase(Locale.ROOT), j.sourceJobId(), j.companyName(), j.companySlug(),
                j.title(), j.normalizedTitle(), j.location(), j.country(), j.remote(), j.employmentType(),
                j.seniority(), j.department(), j.descriptionHtml(), j.descriptionText(), j.applyUrl(),
                j.salaryMin(), j.salaryMax(), j.salaryCurrency(), p.getPostedAt(), p.getFirstSeenAt(),
                p.getLastSeenAt(), j.skills(), vector, model, p.isGhostJobSuspected(), j.contentHash());
    }

    /** Prefer the higher-priority source, then the more recently posted copy. */
    private static NormalizedJob better(NormalizedJob a, NormalizedJob b) {
        Comparator<NormalizedJob> order = Comparator
                .comparingInt((NormalizedJob j) -> j.sourceType().priority())
                .thenComparing(NormalizedJob::postedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
        return order.compare(a, b) >= 0 ? a : b;
    }
}
