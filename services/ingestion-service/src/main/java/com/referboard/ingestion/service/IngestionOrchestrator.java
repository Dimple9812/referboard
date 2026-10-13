package com.referboard.ingestion.service;

import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.domain.IngestionRun;
import com.referboard.ingestion.domain.Source;
import com.referboard.ingestion.normalize.JobNormalizer;
import com.referboard.ingestion.normalize.NormalizedJob;
import com.referboard.ingestion.repository.IngestionRunRepository;
import com.referboard.ingestion.repository.SourceRepository;
import com.referboard.ingestion.source.JobSource;
import com.referboard.ingestion.source.RawPosting;
import com.referboard.ingestion.source.SourceFetchException;
import com.referboard.ingestion.source.SourceTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One ingestion run: fetch all due sources (bounded concurrency; each host rate-limited), normalise, process each
 * source's batch as it arrives, record per-source health, then close stale postings. One source failing never
 * fails the run; it's recorded and the run ends PARTIAL.
 */
@Service
public class IngestionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

    private final SourceRegistry registry;
    private final SourceRepository sources;
    private final IngestionRunRepository runs;
    private final JobNormalizer normalizer;
    private final PostingProcessor processor;
    private final RunLock lock;
    private final IngestionProperties props;
    private final Clock clock;

    public IngestionOrchestrator(SourceRegistry registry, SourceRepository sources, IngestionRunRepository runs,
                                 JobNormalizer normalizer, PostingProcessor processor, RunLock lock,
                                 IngestionProperties props, Clock clock) {
        this.registry = registry;
        this.sources = sources;
        this.runs = runs;
        this.normalizer = normalizer;
        this.processor = processor;
        this.lock = lock;
        this.props = props;
        this.clock = clock;
    }

    private record Fetched(SourceTarget target, List<RawPosting> postings, SourceFetchException error) {
    }

    /** @return the finished run, or empty if another instance is already running one */
    public Optional<IngestionRun> run(IngestionRun.Trigger trigger) {
        Optional<RunLock.Held> held = lock.tryAcquire();
        if (held.isEmpty()) {
            log.info("Ingestion run skipped: another run holds the lock");
            return Optional.empty();
        }
        try (RunLock.Held ignored = held.get()) {
            MDC.put("ingestionRun", trigger.name());
            return Optional.of(doRun(trigger));
        } finally {
            MDC.remove("ingestionRun");
        }
    }

    private IngestionRun doRun(IngestionRun.Trigger trigger) {
        Instant started = clock.instant();
        IngestionRun run = runs.save(IngestionRun.start(trigger, started));
        List<SourceTarget> targets = registry.dueTargets(started);
        log.info("Ingestion run {} started ({}): {} sources due", run.getId(), trigger, targets.size());

        AtomicReference<PostingProcessor.Stats> stats = new AtomicReference<>(PostingProcessor.Stats.EMPTY);
        Map<String, Integer> dropped = new TreeMap<>();
        List<IngestionRun.SourceError> errors = new ArrayList<>();
        int[] counters = new int[4]; // ok, failed, fetched, kept

        Flux.fromIterable(targets)
                .flatMap(this::fetch, Math.max(1, props.maxConcurrentSources()))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(f -> {
                    Instant now = clock.instant();
                    if (f.error() != null) {
                        counters[1]++;
                        errors.add(new IngestionRun.SourceError(f.target().sourceKey(), f.error().kind().name(),
                                f.error().getMessage()));
                        recordFailure(f.target(), f.error(), now);
                        return;
                    }
                    counters[0]++;
                    counters[2] += f.postings().size();
                    List<NormalizedJob> kept = new ArrayList<>();
                    for (RawPosting raw : f.postings()) {
                        switch (normalizer.normalize(raw)) {
                            case JobNormalizer.Kept k -> kept.add(k.job());
                            case JobNormalizer.Dropped d -> dropped.merge(d.reason(), 1, Integer::sum);
                        }
                    }
                    counters[3] += kept.size();
                    try {
                        stats.updateAndGet(s -> s.plus(processor.process(kept, now)));
                        recordSuccess(f.target(), f.postings().size(), now);
                    } catch (Exception e) {
                        log.error("Processing {} failed", f.target().sourceKey(), e);
                        counters[1]++;
                        errors.add(new IngestionRun.SourceError(f.target().sourceKey(), "PROCESSING", e.getMessage()));
                    }
                })
                .blockLast();

        int closed = processor.closeStale(clock.instant(), props.closeAfter());
        PostingProcessor.Stats s = stats.get();

        run.setSourcesOk(counters[0]);
        run.setSourcesFailed(counters[1]);
        run.setFetched(counters[2]);
        run.setKept(counters[3]);
        run.setCreated(s.created());
        run.setUpdated(s.updated());
        run.setUnchanged(s.unchanged());
        run.setClosed(closed);
        run.setErrors(errors.size() > 200 ? errors.subList(0, 200) : errors);
        run.setFinishedAt(clock.instant());
        run.setStatus(counters[1] == 0 ? IngestionRun.Status.SUCCEEDED
                : counters[0] == 0 && !targets.isEmpty() ? IngestionRun.Status.FAILED : IngestionRun.Status.PARTIAL);
        runs.save(run);
        log.info("Ingestion run {} {} in {}s: sources ok={} failed={}, fetched={}, kept={}, created={}, updated={}, "
                        + "unchanged={}, shadowed={}, closed={}, dropped={}",
                run.getId(), run.getStatus(), java.time.Duration.between(started, run.getFinishedAt()).toSeconds(),
                counters[0], counters[1], counters[2], counters[3], s.created(), s.updated(), s.unchanged(),
                s.shadowed(), closed, dropped);
        return run;
    }

    private Mono<Fetched> fetch(SourceTarget target) {
        JobSource source = registry.sourceFor(target.type());
        return Mono.defer(() -> source.fetch(target))
                .map(postings -> new Fetched(target, postings, null))
                .onErrorResume(e -> Mono.just(new Fetched(target, List.of(),
                        e instanceof SourceFetchException sfe ? sfe
                                : new SourceFetchException(SourceFetchException.Kind.FAILED, e.toString(), e))));
    }

    private void recordSuccess(SourceTarget target, int count, Instant now) {
        sources.findById(target.sourceKey()).ifPresent(s -> {
            s.recordSuccess(now, count);
            sources.save(s);
        });
    }

    private void recordFailure(SourceTarget target, SourceFetchException e, Instant now) {
        Source.Status status = switch (e.kind()) {
            case NOT_FOUND -> Source.Status.NOT_FOUND;
            case BLOCKED_BY_ROBOTS -> Source.Status.BLOCKED;
            default -> Source.Status.FAILING;
        };
        if (status == Source.Status.FAILING) {
            log.warn("Source {} failed: {}", target.sourceKey(), e.getMessage());
        } else {
            log.info("Source {} marked {}: {}", target.sourceKey(), status, e.getMessage());
        }
        sources.findById(target.sourceKey()).ifPresent(s -> {
            s.recordFailure(now, status, e.getMessage());
            sources.save(s);
        });
    }
}
