package com.referboard.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.analyzer.domain.JdAnalysis;
import com.referboard.analyzer.domain.ResumeVersion;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.render.DocxRenderer;
import com.referboard.analyzer.render.PdfRenderer;
import com.referboard.analyzer.repository.ResumeVersionRepository;
import com.referboard.analyzer.resume.VersionContent;
import com.referboard.analyzer.scoring.CachingEmbeddings;
import com.referboard.analyzer.scoring.MatchScorer;
import com.referboard.analyzer.suggest.ResumeEditor;
import com.referboard.analyzer.suggest.Suggestion;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisReport;
import com.referboard.analyzer.web.dto.AnalyzerDtos.Decision;
import com.referboard.analyzer.web.dto.AnalyzerDtos.Format;
import com.referboard.analyzer.web.dto.AnalyzerDtos.GenerateRequest;
import com.referboard.analyzer.web.dto.AnalyzerDtos.VersionResponse;
import com.referboard.ai.EmbeddingClient;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.web.ApiException;
import com.referboard.events.ResumeVersionCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tailored resume versions: apply the suggestions the user accepted (claims need explicit confirmation), store the
 * resulting content linked to the analysis and job, publish ResumeVersionCreated, and render DOCX/PDF on download.
 */
@Service
public class ResumeVersionService {

    static final int MAX_VERSIONS_PER_USER = 200;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public record RenderedFile(byte[] bytes, String fileName, String contentType) {
    }

    private final AnalysisService analyses;
    private final ResumeVersionRepository versions;
    private final VersionContent contentJson;
    private final MatchScorer scorer;
    private final EmbeddingClient embeddings;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ResumeVersionService(AnalysisService analyses, ResumeVersionRepository versions, VersionContent contentJson,
                                MatchScorer scorer, EmbeddingClient embeddings, EventPublisher events,
                                EventJson eventJson, ObjectMapper mapper, TransactionTemplate tx, Clock clock) {
        this.analyses = analyses;
        this.versions = versions;
        this.contentJson = contentJson;
        this.scorer = scorer;
        this.embeddings = embeddings;
        this.events = events;
        this.eventJson = eventJson;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    public VersionResponse generate(UUID userId, UUID analysisId, GenerateRequest req) {
        JdAnalysis analysis = analyses.find(userId, analysisId);
        AnalysisReport report = analyses.read(analysis);
        ResumeContent original = contentJson.read(analysis.getResumeContent());

        Map<String, Suggestion> byId = new HashMap<>();
        report.suggestions().forEach(s -> byId.put(s.id(), s));
        List<Suggestion> accepted = new ArrayList<>();
        List<String> unconfirmed = new ArrayList<>();
        for (Decision d : req.decisions()) {
            Suggestion s = byId.get(d.suggestionId());
            if (s == null) {
                throw ApiException.badRequest("UNKNOWN_SUGGESTION", "No suggestion " + d.suggestionId()
                        + " in this analysis.");
            }
            if (!d.accepted() || !s.applicable()) {
                continue;
            }
            if (s.requiresConfirmation() && !d.confirmed()) {
                unconfirmed.add(s.title());
                continue;
            }
            accepted.add(s);
        }
        if (!unconfirmed.isEmpty()) {
            throw ApiException.badRequest("CONFIRMATION_REQUIRED", "Confirm you have this experience before adding it: "
                    + String.join("; ", unconfirmed) + ". (" + Suggestion.CONFIRM_LABEL + ")");
        }
        ResumeContent tailored = ResumeEditor.apply(original, accepted);

        CachingEmbeddings emb = new CachingEmbeddings(embeddings);
        int base = scorer.score(original, original.toPlainText(), null, report.parsedJd(), emb).score();
        int after = scorer.score(tailored, tailored.toPlainText(), null, report.parsedJd(), emb).score();
        int scoreAfter = Math.max(0, Math.min(100, report.score() + (after - base)));

        Format format = req.format() == null ? Format.DOCX : req.format();
        String name = req.name() != null && !req.name().isBlank() ? req.name().trim() : defaultName(report);
        return tx.execute(status -> {
            if (versions.countByUserId(userId) >= MAX_VERSIONS_PER_USER) {
                throw ApiException.conflict("VERSION_LIMIT", "You have " + MAX_VERSIONS_PER_USER
                        + " tailored resumes. Delete some to create more.");
            }
            ResumeVersion v = new ResumeVersion(UUID.randomUUID(), userId);
            v.setName(name);
            v.setJobId(analysis.getJobId());
            v.setAnalysisId(analysis.getId());
            v.setBaseSource(analysis.getResumeSource());
            v.setBaseRefId(analysis.getResumeRefId());
            v.setContent(contentJson.write(tailored));
            v.setAcceptedSuggestions(json(accepted.stream().map(Suggestion::id).toList()));
            v.setFileFormat(format.name());
            v.setScoreBefore((short) report.score());
            v.setScoreAfter((short) scoreAfter);
            versions.saveAndFlush(v);
            events.publish(new ResumeVersionCreatedEvent(eventJson.newMetadata("ResumeVersionCreated"), userId,
                    v.getId(), name, analysis.getJobId(), analysis.getId(), report.score(), scoreAfter, format.name()));
            return toResponse(v, accepted.size(), null);
        });
    }

    @Transactional(readOnly = true)
    public List<VersionResponse> list(UUID userId) {
        return versions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(v -> toResponse(v, accepted(v), null)).toList();
    }

    @Transactional(readOnly = true)
    public VersionResponse get(UUID userId, UUID id) {
        ResumeVersion v = find(userId, id);
        return toResponse(v, accepted(v), contentJson.read(v));
    }

    @Transactional(readOnly = true)
    public RenderedFile file(UUID userId, UUID id, Format format) {
        ResumeVersion v = find(userId, id);
        Format f = format == null ? Format.valueOf(v.getFileFormat()) : format;
        ResumeContent c = contentJson.read(v);
        String base = fileBase(c, v.getName());
        return f == Format.PDF
                ? new RenderedFile(PdfRenderer.render(c), base + ".pdf", "application/pdf")
                : new RenderedFile(DocxRenderer.render(c), base + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        find(userId, id).setDeletedAt(clock.instant());
    }

    private ResumeVersion find(UUID userId, UUID id) {
        return versions.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Resume version", id));
    }

    private VersionResponse toResponse(ResumeVersion v, int accepted, ResumeContent content) {
        return new VersionResponse(v.getId(), v.getName(), v.getJobId(), v.getAnalysisId(),
                Format.valueOf(v.getFileFormat()), v.getScoreBefore() == null ? null : (int) v.getScoreBefore(),
                v.getScoreAfter() == null ? null : (int) v.getScoreAfter(), accepted,
                v.getCreatedAt() == null ? clock.instant() : v.getCreatedAt(),
                "/api/resume-versions/" + v.getId() + "/file", content);
    }

    private int accepted(ResumeVersion v) {
        try {
            return mapper.readTree(v.getAcceptedSuggestions()).size();
        } catch (JsonProcessingException e) {
            return 0;
        }
    }

    private String defaultName(AnalysisReport r) {
        String target = r.job().companyName() != null ? r.job().companyName()
                + (r.job().title() != null ? " – " + r.job().title() : "")
                : r.job().title() != null ? r.job().title() : "Tailored resume";
        return target + " (" + LocalDate.now(clock.withZone(ZoneOffset.UTC)).format(DAY) + ")";
    }

    /** "Priya_Sharma_Northwind" style: safe for every OS and mail client. */
    static String fileBase(ResumeContent c, String versionName) {
        String person = c.fullName() == null ? "Resume" : c.fullName();
        String label = versionName == null ? "" : versionName.replaceAll("\\(.*?\\)", "").split("[–-]")[0];
        String raw = (person + " " + label).trim();
        String safe = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        return (safe.isEmpty() ? "Resume" : safe.length() > 80 ? safe.substring(0, 80) : safe) + "_Resume";
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
