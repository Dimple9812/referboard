package com.referboard.analyzer.web.dto;

import com.referboard.analyzer.letter.LetterLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LetterDtos {

    private LetterDtos() {
    }

    public record CreateLetterRequest(
            @Schema(description = "STANDARD (one page, default) or SHORT (a few sentences for an email or form)")
            LetterLength length,
            @Size(max = 600)
            @Schema(description = "Optional: why this company, in your words. Used as written; never invented.")
            String motivation) {
    }

    public record UpdateLetterRequest(
            @NotBlank @Size(max = 8000) String body,
            @Schema(description = "The version you edited; a stale one gets 409 CONCURRENT_UPDATE") Long version) {
    }

    public record LetterResponse(
            UUID id,
            UUID analysisId,
            UUID jobId,
            String jobTitle,
            String companyName,
            LetterLength length,
            @Schema(description = "TEMPLATE (built only from your resume) or AI (checked against your resume)")
            String origin,
            String body,
            @Schema(description = "The original draft; only on the detail and create endpoints") String generatedBody,
            boolean edited,
            String motivation,
            @Schema(description = "Why the letter was built the way it was; only on create") List<String> notices,
            Instant createdAt,
            Instant updatedAt,
            long version,
            @Schema(description = "GET this path with ?format=DOCX or PDF to download") String downloadPath) {
    }
}
