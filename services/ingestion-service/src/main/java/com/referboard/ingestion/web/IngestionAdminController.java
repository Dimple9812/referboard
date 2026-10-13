package com.referboard.ingestion.web;

import com.referboard.common.web.ApiException;
import com.referboard.ingestion.domain.IngestionRun;
import com.referboard.ingestion.domain.Source;
import com.referboard.ingestion.repository.IngestedPostingRepository;
import com.referboard.ingestion.repository.IngestionRunRepository;
import com.referboard.ingestion.repository.SourceRepository;
import com.referboard.ingestion.service.IngestionScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operator endpoints; ingestion has no user-facing API (jobs are served by job-service). */
@RestController
@RequestMapping("/api/ingestion")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ingestion admin")
public class IngestionAdminController {

    private final IngestionScheduler scheduler;
    private final IngestionRunRepository runs;
    private final SourceRepository sources;
    private final IngestedPostingRepository postings;

    public IngestionAdminController(IngestionScheduler scheduler, IngestionRunRepository runs,
                                    SourceRepository sources, IngestedPostingRepository postings) {
        this.scheduler = scheduler;
        this.runs = runs;
        this.sources = sources;
        this.postings = postings;
    }

    public record RunView(UUID id, String trigger, String status, Instant startedAt, Instant finishedAt,
                          int sourcesOk, int sourcesFailed, int fetched, int kept, int created, int updated,
                          int unchanged, int closed, List<IngestionRun.SourceError> errors) {
        static RunView of(IngestionRun r) {
            return new RunView(r.getId(), r.getTrigger().name(), r.getStatus().name(), r.getStartedAt(),
                    r.getFinishedAt(), r.getSourcesOk(), r.getSourcesFailed(), r.getFetched(), r.getKept(),
                    r.getCreated(), r.getUpdated(), r.getUnchanged(), r.getClosed(), r.getErrors());
        }
    }

    public record SourceView(String sourceKey, String type, String slug, String name, boolean enabled, String status,
                             Instant lastSuccessAt, Instant lastAttemptAt, Integer lastJobCount, String lastError,
                             int consecutiveFailures) {
        static SourceView of(Source s) {
            return new SourceView(s.getSourceKey(), s.getSourceType().name(), s.getSlug(), s.getDisplayName(),
                    s.isEnabled(), s.getStatus().name(), s.getLastSuccessAt(), s.getLastAttemptAt(),
                    s.getLastJobCount(), s.getLastError(), s.getConsecutiveFailures());
        }
    }

    @PostMapping("/runs")
    @Operation(summary = "Start an ingestion run now (runs in the background)")
    public ResponseEntity<Map<String, Object>> trigger() {
        if (!scheduler.trigger(IngestionRun.Trigger.MANUAL)) {
            throw ApiException.conflict("RUN_IN_PROGRESS", "An ingestion run is already in progress");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("started", true));
    }

    @GetMapping("/runs")
    @Operation(summary = "Recent runs, newest first")
    public List<RunView> runs(@RequestParam(defaultValue = "20") int limit) {
        return runs.findAllByOrderByStartedAtDesc(PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream().map(RunView::of).toList();
    }

    @GetMapping("/sources")
    @Operation(summary = "Source health; filter with status=NOT_FOUND to find stale slugs in companies.yml")
    public List<SourceView> sources(@RequestParam(required = false) Source.Status status) {
        return sources.findAllByOrderByStatusAscSourceKeyAsc().stream()
                .filter(s -> status == null || s.getStatus() == status)
                .map(SourceView::of).toList();
    }

    @GetMapping("/stats")
    @Operation(summary = "Headline numbers")
    public Map<String, Object> stats() {
        return Map.of("openPostings", postings.countByClosedAtIsNull(), "running", scheduler.isRunning());
    }
}
