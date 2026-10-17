package com.referboard.analyzer.web.dto;

import com.referboard.analyzer.prep.PrepItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PrepDtos {

    private PrepDtos() {
    }

    public enum Confidence { NEW, SHAKY, READY }

    public record SaveAnswerRequest(
            @Size(max = 4000) @Schema(description = "Your notes for this answer (only you see them)") String notes,
            @NotNull Confidence confidence) {
    }

    public record ItemView(
            String id, PrepItem.Section section, String question, String why, List<String> pointers,
            PrepItem.Example example, String skill, PrepItem.Source source,
            String notes, Confidence confidence, Instant updatedAt) {
    }

    public record Progress(int total, int ready, int shaky, int notStarted) {
    }

    public record PrepResponse(
            UUID id, UUID analysisId, UUID jobId, String jobTitle, String companyName, Instant createdAt,
            boolean aiQuestions, Progress progress, List<ItemView> items,
            @Schema(description = "Only when the plan was just created") List<String> notices,
            @Schema(description = "True when a plan already existed for this analysis and was returned") boolean existing) {
    }

    public record PrepSummary(UUID id, UUID analysisId, UUID jobId, String jobTitle, String companyName,
                              Instant createdAt, Progress progress) {
    }
}
