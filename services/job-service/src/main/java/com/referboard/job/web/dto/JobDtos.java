package com.referboard.job.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JobDtos {

    private JobDtos() {
    }

    /** One skill: canonical name for filtering, label for display. */
    public record Skill(String name, String label) {
    }

    public record JobCard(
            UUID id,
            String title,
            String companyName,
            String location,
            boolean remote,
            String seniority,
            String employmentType,
            Instant postedAt,
            Long salaryMin,
            Long salaryMax,
            String salaryCurrency,
            List<Skill> skills,
            @Schema(description = "Listed for 60+ days; may not be actively hiring") boolean ghostJobSuspected,
            String applyUrl,
            String source,
            @Schema(description = "Null until the user has a profile") Integer matchPercent,
            List<Skill> matchedSkills
    ) {
    }

    public record SearchResponse(List<JobCard> items, int page, int size, long total, boolean hasNext) {
    }

    public record MatchView(
            boolean available,
            @Schema(description = "Why no score: e.g. PROFILE_MISSING") String unavailableReason,
            Integer matchPercent,
            @Schema(description = "0..1 per component; null when the signal isn't available") Map<String, Double> components,
            @Schema(description = "Effective weight of each component after renormalisation") Map<String, Double> weights,
            List<Skill> matchedSkills,
            List<Skill> missingSkills,
            List<String> reasons
    ) {
        public static MatchView unavailable(String reason) {
            return new MatchView(false, reason, null, Map.of(), Map.of(), List.of(), List.of(), List.of());
        }
    }

    public record JobDetail(
            JobCard job,
            String descriptionHtml,
            String department,
            String country,
            String status,
            Instant closedAt,
            Instant firstSeenAt,
            Instant lastSeenAt,
            MatchView match
    ) {
    }
}
