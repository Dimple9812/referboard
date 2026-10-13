package com.referboard.job.web;

import com.referboard.common.security.CurrentUser;
import com.referboard.job.store.SearchCriteria;
import com.referboard.job.service.JobQueryService;
import com.referboard.job.web.dto.JobDtos.JobDetail;
import com.referboard.job.web.dto.JobDtos.MatchView;
import com.referboard.job.web.dto.JobDtos.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@Validated
@Tag(name = "Jobs")
public class JobController {

    private final JobQueryService jobs;

    public JobController(JobQueryService jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    @Operation(summary = "Search open jobs. Every result carries your live match % once your profile exists.")
    public SearchResponse search(
            @Parameter(description = "Free text (supports \"quoted phrases\", -exclude, OR)")
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) @Size(max = 100) String role,
            @Parameter(description = "City, any spelling (Bangalore = Bengaluru)")
            @RequestParam(required = false) @Size(max = 100) String location,
            @Parameter(description = "true = remote only, false = on-site/hybrid only")
            @RequestParam(required = false) Boolean remote,
            @RequestParam(required = false) @Min(1) @Max(90) Integer postedWithinDays,
            @RequestParam(required = false) @Size(max = 100) String company,
            @RequestParam(required = false) @Size(max = 6)
            List<@Pattern(regexp = "(?i)INTERN|ENTRY|MID|SENIOR|LEAD|PRINCIPAL") String> seniority,
            @RequestParam(required = false) @Size(max = 10) List<@Size(max = 50) String> skills,
            @RequestParam(defaultValue = "false") boolean hideGhostJobs,
            @Parameter(description = "RELEVANCE (needs q; else falls back to RECENT), RECENT, MATCH")
            @RequestParam(defaultValue = "RELEVANCE") SearchCriteria.Sort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return jobs.search(CurrentUser.id(), new SearchCriteria(q, role, location, remote, postedWithinDays, company,
                seniority, skills, hideGhostJobs, sort, page, size));
    }

    @GetMapping("/recommended")
    @Operation(summary = "Your best matches, highest first")
    public SearchResponse recommended(@RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return jobs.recommended(CurrentUser.id(), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Job detail with description and your match breakdown")
    public JobDetail detail(@PathVariable UUID id) {
        return jobs.detail(CurrentUser.id(), id);
    }

    @GetMapping("/{id}/match")
    @Operation(summary = "Just the match breakdown (components, weights, matched/missing skills, reasons)")
    public MatchView match(@PathVariable UUID id) {
        return jobs.match(CurrentUser.id(), id);
    }
}
