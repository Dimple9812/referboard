package com.referboard.analyzer.web.dto;

import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.resume.LoadedResume;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.analyzer.suggest.Suggestion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnalyzerDtos {

    private AnalyzerDtos() {
    }

    // ---------- requests ----------

    @Schema(description = "Pick which resume to compare. Default: your primary uploaded resume.")
    public record ResumeSelection(
            @Schema(description = "PROFILE_RESUME (an uploaded resume) or VERSION (a tailored version)")
            LoadedResume.Source source,
            UUID id) {
    }

    @Schema(description = "Give jdText (pasted), or jobId (a ReferBoard job), or both (pasted text linked to a job)")
    public record AnalyzeRequest(
            @Size(max = 60000) String jdText,
            UUID jobId,
            @Size(max = 300) String jobTitle,
            @Size(max = 200) String companyName,
            @Valid ResumeSelection resume) {
    }

    public enum Format { DOCX, PDF }

    public record Decision(@NotNull String suggestionId, boolean accepted,
                           @Schema(description = "Required for suggestions marked requiresConfirmation") boolean confirmed) {
    }

    public record GenerateRequest(
            @NotNull @Size(max = 100) List<@Valid Decision> decisions,
            Format format,
            @Size(max = 200) String name) {
    }

    // ---------- responses ----------

    public record JobRef(UUID jobId, String title, String companyName, String jdSource) {
    }

    public record ResumeRef(LoadedResume.Source source, UUID id, String name) {
    }

    public record AnalysisReport(
            UUID id,
            Instant createdAt,
            JobRef job,
            ResumeRef resume,
            int score,
            MatchReport.Verdict verdict,
            List<MatchReport.Component> breakdown,
            List<MatchReport.SkillMatch> matchedSkills,
            MatchReport.MissingSkills missingSkills,
            List<MatchReport.AtsCheck> atsChecks,
            @Schema(description = "Ordered: highest priority and impact first") List<Suggestion> suggestions,
            @Schema(description = "If you accept every suggestion that needs no confirmation") int projectedScore,
            @Schema(description = "If you also accept the ones marked 'Add only if you have this experience'")
            int projectedScoreWithConfirmed,
            ParsedJd parsedJd,
            @Schema(description = "LLM or HEURISTIC") String jdParser,
            boolean aiSuggestions,
            List<String> notices,
            @Schema(description = "True when an identical analysis from the last 24h was returned (not counted)")
            boolean reused
    ) {
        public AnalysisReport asReused() {
            return new AnalysisReport(id, createdAt, job, resume, score, verdict, breakdown, matchedSkills,
                    missingSkills, atsChecks, suggestions, projectedScore, projectedScoreWithConfirmed, parsedJd,
                    jdParser, aiSuggestions, notices, true);
        }

        public AnalysisReport withCreatedAt(Instant at) {
            return new AnalysisReport(id, at, job, resume, score, verdict, breakdown, matchedSkills, missingSkills,
                    atsChecks, suggestions, projectedScore, projectedScoreWithConfirmed, parsedJd, jdParser,
                    aiSuggestions, notices, reused);
        }
    }

    public record AnalysisSummary(UUID id, Instant createdAt, UUID jobId, String jobTitle, String companyName,
                                  int score, MatchReport.Verdict verdict, int projectedScore, String resumeName) {
    }

    public record AnalysisPage(List<AnalysisSummary> items, int page, int size, long total, boolean hasNext) {
    }

    public record Usage(String period, int used, Integer limit, Integer remaining, boolean unlimited,
                        String resetsOn) {
    }

    public record VersionResponse(
            UUID id,
            String name,
            UUID jobId,
            UUID analysisId,
            Format fileFormat,
            Integer scoreBefore,
            Integer scoreAfter,
            int acceptedSuggestions,
            Instant createdAt,
            @Schema(description = "GET this path (add ?format=PDF or DOCX) to download") String downloadPath,
            @Schema(description = "Structured content; only on the detail endpoint")
            com.referboard.analyzer.model.ResumeContent content) {
    }

    @Schema(description = "What an analysis compared, for interview prep: the parsed posting, the skill matches and the "
            + "resume content used. The caller's own data only.")
    public record AnalysisContext(
            UUID analysisId,
            JobRef job,
            ParsedJd parsedJd,
            List<MatchReport.SkillMatch> matchedSkills,
            MatchReport.MissingSkills missingSkills,
            String yearsOfExperience,
            com.referboard.analyzer.model.ResumeContent resume) {
    }
}
