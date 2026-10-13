package com.referboard.referral.web.dto;

import com.referboard.referral.domain.ContactStatus;
import com.referboard.referral.domain.MessageDraft;
import com.referboard.referral.domain.PersonType;
import com.referboard.referral.domain.Reminder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ReferralDtos {

    private ReferralDtos() {
    }

    // ---------- requests ----------

    public record CreateContactRequest(
            @NotBlank @Size(max = 200) String fullName,
            @Schema(description = "LinkedIn profile URL (linkedin.com/in/...) or another https link. Never fetched.")
            @Size(max = 500) String profileUrl,
            @Schema(description = "Their headline as you see it, e.g. 'SDE II at Acme | ex-Flipkart'")
            @Size(max = 300) String headline,
            @NotNull PersonType personType,
            @Size(max = 2000) String notes
    ) {
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateContactRequest(
            @Size(min = 1, max = 200) String fullName,
            @Size(max = 500) String profileUrl,
            @Size(max = 300) String headline,
            PersonType personType,
            @Size(max = 2000) String notes
    ) {
    }

    public record ChangeStatusRequest(@NotNull ContactStatus status) {
    }

    public enum DraftType { INTRO, FOLLOW_UP }

    public record DraftRequest(
            @Schema(description = "INTRO: connection note + referral request (default). FOLLOW_UP: nudge after no reply.")
            DraftType type,
            @Schema(description = "Something true you'd like mentioned, e.g. 'we met at JSConf India'. Used verbatim as a fact.")
            @Size(max = 300) String context
    ) {
    }

    public record UpdateReminderRequest(
            @NotNull @Schema(allowableValues = {"DONE", "DISMISSED"}) Reminder.Status status) {
    }

    // ---------- responses ----------

    public record StatusHistoryDto(ContactStatus fromStatus, ContactStatus toStatus, Instant changedAt) {
    }

    public record ContactResponse(
            UUID id,
            UUID jobId,
            String jobTitle,
            String companyName,
            String fullName,
            String profileUrl,
            String headline,
            PersonType personType,
            ContactStatus status,
            @Schema(description = "Statuses this contact can move to next") Set<ContactStatus> allowedNextStatuses,
            Instant requestSentAt,
            Instant connectedAt,
            Instant referralAskedAt,
            Instant referredAt,
            Instant noResponseAt,
            String notes,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ContactDetailResponse(
            ContactResponse contact,
            List<StatusHistoryDto> history,
            List<DraftResponse> drafts,
            List<ReminderResponse> reminders
    ) {
    }

    public record ContactPage(List<ContactResponse> items, int page, int size, long total, boolean hasNext) {
    }

    public record PipelineSummary(Map<ContactStatus, Long> byStatus, long total, long activeReminders) {
    }

    public record DraftResponse(
            UUID id,
            MessageDraft.Kind kind,
            MessageDraft.Tone tone,
            String content,
            int charCount,
            @Schema(description = "'template' or the LLM id, e.g. anthropic:claude-sonnet-5-5") String model,
            Instant createdAt,
            Instant copiedAt
    ) {
    }

    public record DraftSetResponse(
            UUID contactId,
            DraftType type,
            @Schema(description = "AI or TEMPLATE") String generatedBy,
            List<String> notices,
            List<DraftResponse> drafts
    ) {
    }

    public record ReminderResponse(
            UUID id,
            UUID contactId,
            String contactName,
            UUID jobId,
            String jobTitle,
            String companyName,
            Reminder.Type type,
            Reminder.Status status,
            Instant dueAt,
            Instant firedAt,
            String message,
            String draftedFollowUp
    ) {
    }
}
