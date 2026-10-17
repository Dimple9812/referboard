package com.referboard.interview.web.dto;

import com.referboard.interview.domain.PrepItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PrepDtos {

    private PrepDtos() {
    }

    public record CreatePrepRequest(@NotNull UUID analysisId) {
    }

    public record UpdateItemRequest(
            @Size(max = 4000) @Schema(description = "Your own answer notes") String notes,
            PrepItem.Confidence confidence,
            @Schema(description = "The version you edited; a stale one gets 409") Long version) {
    }

    public record ItemView(UUID id, String section, String question, String checks, String tip,
                           @Schema(description = "One of your resume bullets, verbatim") String evidence,
                           String evidenceFrom, String skill, String origin, String notes, String confidence,
                           long version) {
    }

    public record PackSummary(UUID id, UUID analysisId, UUID jobId, String jobTitle, String companyName, String origin,
                              Instant createdAt, int total, int practising, int confident) {
    }

    public record PackDetail(UUID id, UUID analysisId, UUID jobId, String jobTitle, String companyName, String origin,
                             Instant createdAt, List<ItemView> items,
                             @Schema(description = "Only on create") List<String> notices) {
    }

    public record Usage(String period, int used, Integer limit, Integer remaining, boolean unlimited, String resetsOn) {
    }
}
