package com.referboard.profile.web.dto;

import com.referboard.profile.domain.JobPreferences;
import com.referboard.profile.parsing.ParsedResume;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public final class ProfileDtos {

    private ProfileDtos() {
    }

    // ---------- responses ----------

    public record ProfileResponse(
            UUID userId,
            @Schema(description = "False until the user uploads a resume or saves the profile") boolean exists,
            String fullName,
            String email,
            String phone,
            String headline,
            String summary,
            String currentTitle,
            String currentCompany,
            String location,
            BigDecimal totalExperienceYears,
            String college,
            String highestDegree,
            List<String> links,
            List<String> certifications,
            List<SkillDto> skills,
            List<ExperienceDto> experiences,
            List<EducationDto> educations,
            PreferencesDto preferences,
            long profileVersion,
            @Schema(description = "LLM | HEURISTIC | MANUAL — HEURISTIC means: ask the user to review carefully")
            String parseSource,
            UUID primaryResumeId,
            boolean embeddingReady,
            Instant updatedAt
    ) {
    }

    public record SkillDto(String name, String displayName, BigDecimal years, String source, String evidence) {
    }

    public record ExperienceDto(UUID id, String company, String title, String location, YearMonth startDate,
                                YearMonth endDate, boolean current, String description, List<String> bullets) {
    }

    public record EducationDto(UUID id, String institution, String degree, String fieldOfStudy, Integer startYear,
                               Integer endYear, String grade) {
    }

    public record PreferencesDto(List<String> roles, List<String> locations, boolean remoteOnly,
                                 boolean openToRemote, Long minSalaryInr, String experienceLevel) {
    }

    public record ResumeResponse(UUID id, String fileName, String contentType, long sizeBytes, String parseStatus,
                                 String parseError, String parser, boolean primary, Instant createdAt,
                                 Instant parsedAt) {
    }

    public record ParsedResumeResponse(UUID resumeId, String parser, String parserModel, ParsedResume parsed,
                                       String text) {
    }

    // ---------- requests ----------

    /** Full replacement of the editable profile (what the onboarding "review your profile" screen submits). */
    public record UpdateProfileRequest(
            @Size(max = 200) String fullName,
            @Email @Size(max = 320) String email,
            @Size(max = 40) String phone,
            @Size(max = 300) String headline,
            @Size(max = 5000) String summary,
            @Size(max = 200) String currentTitle,
            @Size(max = 200) String currentCompany,
            @Size(max = 200) String location,
            @Size(max = 300) String college,
            @Size(max = 200) String highestDegree,
            @Size(max = 10) List<@NotBlank @Size(max = 300) String> links,
            @Size(max = 30) List<@NotBlank @Size(max = 300) String> certifications,
            @NotNull @Size(max = 100) List<@Valid SkillInput> skills,
            @NotNull @Size(max = 30) List<@Valid ExperienceInput> experiences,
            @NotNull @Size(max = 10) List<@Valid EducationInput> educations
    ) {
    }

    public record SkillInput(
            @NotBlank @Size(max = 100) String name,
            @DecimalMin("0") @DecimalMax("50") BigDecimal years
    ) {
    }

    public record ExperienceInput(
            @NotBlank @Size(max = 200) String company,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 200) String location,
            @NotNull @Schema(type = "string", example = "2022-07") YearMonth startDate,
            @Schema(type = "string", example = "2024-03") YearMonth endDate,
            boolean current,
            @Size(max = 5000) String description,
            @Size(max = 30) List<@NotBlank @Size(max = 1000) String> bullets
    ) {
        @AssertTrue(message = "endDate must be on or after startDate, and absent for a current role")
        @Schema(hidden = true)
        public boolean isDateRangeValid() {
            if (current) {
                return endDate == null;
            }
            return startDate == null || endDate == null || !endDate.isBefore(startDate);
        }

        @AssertTrue(message = "dates cannot be in the future")
        @Schema(hidden = true)
        public boolean isNotInFuture() {
            YearMonth now = YearMonth.now();
            return (startDate == null || !startDate.isAfter(now)) && (endDate == null || !endDate.isAfter(now));
        }
    }

    public record EducationInput(
            @NotBlank @Size(max = 300) String institution,
            @Size(max = 200) String degree,
            @Size(max = 200) String fieldOfStudy,
            @Min(1950) @Max(2100) Integer startYear,
            @Min(1950) @Max(2100) Integer endYear,
            @Size(max = 50) String grade
    ) {
    }

    public record PreferencesRequest(
            @NotNull @Size(max = 10) List<@NotBlank @Size(max = 100) String> roles,
            @NotNull @Size(max = 10) List<@NotBlank @Size(max = 100) String> locations,
            boolean remoteOnly,
            boolean openToRemote,
            @PositiveOrZero Long minSalaryInr,
            JobPreferences.ExperienceLevel experienceLevel
    ) {
    }
}
