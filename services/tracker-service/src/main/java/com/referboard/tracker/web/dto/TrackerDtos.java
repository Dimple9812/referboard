package com.referboard.tracker.web.dto;

import com.referboard.tracker.domain.Application;
import com.referboard.tracker.domain.ApplicationEvent;
import com.referboard.tracker.domain.Stage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TrackerDtos {

    private TrackerDtos() {
    }

    // ---------- requests ----------

    @Schema(description = "Either jobId (a ReferBoard job) or jobTitle + companyName (a job found elsewhere)")
    public record CreateApplicationRequest(
            UUID jobId,
            @Size(max = 300) String jobTitle,
            @Size(max = 200) String companyName,
            @Size(max = 300) String location,
            @Size(max = 2000) String applyUrl,
            @Schema(description = "Default SAVED") Stage stage,
            @Schema(description = "When you applied, if already applied") Instant appliedAt,
            @Size(max = 10000) String notes
    ) {
    }

    /** Partial update; null leaves a field unchanged. Set clearNextAction to remove the next step. */
    public record UpdateApplicationRequest(
            @Size(min = 1, max = 300) String jobTitle,
            @Size(min = 1, max = 200) String companyName,
            @Size(max = 300) String location,
            @Size(max = 2000) String applyUrl,
            @Size(max = 10000) String notes,
            Instant appliedAt,
            @Size(max = 200) @Schema(description = "e.g. 'Technical round 2'") String nextAction,
            Instant nextActionAt,
            Boolean clearNextAction
    ) {
    }

    public record MoveRequest(
            @NotNull Stage stage,
            @Min(0) @Schema(description = "Target index within the column; omitted = top") Integer index
    ) {
    }

    public record AddNoteRequest(@NotBlank @Size(max = 2000) String text) {
    }

    // ---------- responses ----------

    public record CardResponse(
            UUID id,
            UUID jobId,
            String jobTitle,
            String companyName,
            String location,
            String applyUrl,
            Stage stage,
            double position,
            Application.Source source,
            UUID resumeVersionId,
            @Schema(description = "Latest referral update, e.g. 'Referred by Rohan Mehta'") String referralSummary,
            String notes,
            Instant appliedAt,
            Instant stageChangedAt,
            String nextAction,
            Instant nextActionAt,
            @Schema(description = "The posting has closed") boolean jobClosed,
            Instant jobClosedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record TimelineEntry(
            UUID id,
            ApplicationEvent.Type type,
            Stage fromStage,
            Stage toStage,
            String message,
            Map<String, Object> details,
            Instant occurredAt
    ) {
    }

    public record ApplicationDetail(CardResponse card, List<TimelineEntry> timeline) {
    }

    public record Column(Stage stage, String label, int count, List<CardResponse> cards) {
    }

    public record Board(List<Column> columns, int total) {
    }
}
