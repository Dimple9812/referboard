package com.referboard.analyzer.service;

import com.referboard.analyzer.config.AnalyzerProperties;
import com.referboard.analyzer.domain.CoverLetter;
import com.referboard.analyzer.domain.JdAnalysis;
import com.referboard.analyzer.letter.AiLetterWriter;
import com.referboard.analyzer.letter.LetterFacts;
import com.referboard.analyzer.letter.LetterGuard;
import com.referboard.analyzer.letter.LetterLength;
import com.referboard.analyzer.letter.TemplateLetterWriter;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.render.LetterRenderer;
import com.referboard.analyzer.repository.CoverLetterRepository;
import com.referboard.analyzer.resume.VersionContent;
import com.referboard.analyzer.scoring.MatchScorer;
import com.referboard.analyzer.suggest.SuggestionEngine;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisReport;
import com.referboard.analyzer.web.dto.AnalyzerDtos.Format;
import com.referboard.analyzer.web.dto.LetterDtos.CreateLetterRequest;
import com.referboard.analyzer.web.dto.LetterDtos.LetterResponse;
import com.referboard.analyzer.web.dto.LetterDtos.UpdateLetterRequest;
import com.referboard.common.web.ApiException;
import com.referboard.skills.SkillTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cover letters from an analysis. With an LLM configured the draft is AI-written and then checked by
 * {@link LetterGuard}; anything it rejects (or no LLM, or the daily AI cap) falls back to the template letter, which
 * can only arrange facts from the resume. The user then edits freely: their saved text is theirs.
 */
@Service
public class CoverLetterService {

    private static final Logger log = LoggerFactory.getLogger(CoverLetterService.class);
    static final int MAX_LETTERS_PER_USER = 200;
    private static final DateTimeFormatter LETTER_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    public record RenderedFile(byte[] bytes, String fileName, String contentType) {
    }

    private final AnalysisService analyses;
    private final CoverLetterRepository letters;
    private final VersionContent contentJson;
    private final MatchScorer scorer;
    private final AiLetterWriter ai;
    private final SkillTaxonomy taxonomy;
    private final AnalyzerProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CoverLetterService(AnalysisService analyses, CoverLetterRepository letters, VersionContent contentJson,
                              MatchScorer scorer, AiLetterWriter ai, SkillTaxonomy taxonomy, AnalyzerProperties props,
                              TransactionTemplate tx, Clock clock) {
        this.analyses = analyses;
        this.letters = letters;
        this.contentJson = contentJson;
        this.scorer = scorer;
        this.ai = ai;
        this.taxonomy = taxonomy;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    public LetterResponse create(UUID userId, UUID analysisId, CreateLetterRequest req) {
        JdAnalysis analysis = analyses.find(userId, analysisId);
        AnalysisReport report = analyses.read(analysis);
        ResumeContent resume = contentJson.read(analysis.getResumeContent());
        LetterLength length = req.length() == null ? LetterLength.STANDARD : req.length();
        String yearsText = SuggestionEngine.yearsText(scorer.years(resume));
        LetterFacts facts = LetterFacts.from(resume, report.parsedJd(), report.matchedSkills(), yearsText,
                report.job().title(), report.job().companyName(), req.motivation(), taxonomy);
        if (letters.countByUserId(userId) >= MAX_LETTERS_PER_USER) {
            throw ApiException.conflict("LETTER_LIMIT", "You have " + MAX_LETTERS_PER_USER
                    + " cover letters. Delete some to write more.");
        }

        List<String> notices = new ArrayList<>();
        String body = null;
        String origin = "TEMPLATE";
        String model = null;
        if (!ai.available()) {
            notices.add("AI drafting isn't configured, so this letter is built only from your resume and the posting.");
        } else if (letters.countAiSince(userId, clock.instant().minus(1, ChronoUnit.DAYS)) >= props.aiLettersPerDay()) {
            notices.add("You've used today's " + props.aiLettersPerDay()
                    + " AI-written letters, so this one is built only from your resume and the posting.");
        } else {
            try {
                String draft = ai.write(facts, resume, report.parsedJd(), length).orElse(null);
                LetterGuard.Verdict v = LetterGuard.check(draft, facts, length, taxonomy);
                if (v.ok()) {
                    body = draft.strip();
                    origin = "AI";
                    model = ai.modelId();
                } else {
                    log.info("AI cover letter rejected ({})", v.reason());
                    notices.add("The AI draft said something your resume doesn't (" + v.reason()
                            + "), so this letter is built only from your resume instead.");
                }
            } catch (RuntimeException e) {
                log.warn("AI cover letter failed: {}", e.toString());
                notices.add("AI drafting is unavailable right now, so this letter is built only from your resume.");
            }
        }
        if (body == null) {
            body = TemplateLetterWriter.write(facts, length);
        }
        if (facts.evidence().isEmpty()) {
            notices.add("None of your resume bullets mention the skills this job asks for, so the letter has no "
                    + "examples. Add one in your own words if you have it.");
        }

        String finalBody = body;
        String finalOrigin = origin;
        String finalModel = model;
        CoverLetter saved = tx.execute(s -> {
            CoverLetter l = new CoverLetter(UUID.randomUUID(), userId);
            l.setAnalysisId(analysis.getId());
            l.setJobId(analysis.getJobId());
            l.setJobTitle(facts.jobTitle());
            l.setCompanyName(facts.company());
            l.setLength(length.name());
            l.setOrigin(finalOrigin);
            l.setModel(finalModel);
            l.setMotivation(facts.motivation());
            l.setSenderName(resume.fullName());
            l.setSenderContact(contactLine(resume));
            l.setGeneratedBody(finalBody);
            l.setBody(finalBody);
            l.setUpdatedAt(clock.instant());
            return letters.saveAndFlush(l);
        });
        return toResponse(saved, true, notices);
    }

    @Transactional(readOnly = true)
    public List<LetterResponse> list(UUID userId, UUID jobId) {
        List<CoverLetter> xs = jobId == null ? letters.findByUserIdOrderByCreatedAtDesc(userId)
                : letters.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
        return xs.stream().map(l -> toResponse(l, false, null)).toList();
    }

    @Transactional(readOnly = true)
    public LetterResponse get(UUID userId, UUID id) {
        return toResponse(find(userId, id), true, null);
    }

    @Transactional
    public LetterResponse update(UUID userId, UUID id, UpdateLetterRequest req) {
        CoverLetter l = find(userId, id);
        if (req.version() != null && req.version() != l.getVersion()) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This letter changed in another tab. Reload to see it.");
        }
        l.setBody(req.body().strip().replace("\r\n", "\n"));
        l.setUpdatedAt(clock.instant());
        letters.flush();
        return toResponse(l, true, null);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        find(userId, id).setDeletedAt(clock.instant());
    }

    @Transactional(readOnly = true)
    public RenderedFile file(UUID userId, UUID id, Format format) {
        CoverLetter l = find(userId, id);
        LetterRenderer.Letter letter = new LetterRenderer.Letter(l.getSenderName(), l.getSenderContact(),
                LocalDate.now(clock.withZone(ZoneId.of(props.letterTimeZone()))).format(LETTER_DATE), l.getBody());
        String base = fileBase(l.getSenderName(), l.getCompanyName());
        return format == Format.PDF
                ? new RenderedFile(LetterRenderer.pdf(letter), base + ".pdf", "application/pdf")
                : new RenderedFile(LetterRenderer.docx(letter), base + ".docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private CoverLetter find(UUID userId, UUID id) {
        return letters.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Cover letter", id));
    }

    private LetterResponse toResponse(CoverLetter l, boolean detail, List<String> notices) {
        return new LetterResponse(l.getId(), l.getAnalysisId(), l.getJobId(), l.getJobTitle(), l.getCompanyName(),
                LetterLength.valueOf(l.getLength()), l.getOrigin(), l.getBody(), detail ? l.getGeneratedBody() : null,
                !l.getBody().equals(l.getGeneratedBody()), l.getMotivation(), notices,
                l.getCreatedAt() == null ? clock.instant() : l.getCreatedAt(), l.getUpdatedAt(), l.getVersion(),
                "/api/cover-letters/" + l.getId() + "/file");
    }

    /** "Priya Sharma | priya@x.com | +91 98…" — the same contact details the resume shows. */
    static String contactLine(ResumeContent c) {
        List<String> parts = new ArrayList<>();
        for (String s : new String[]{c.email(), c.phone(), c.location()}) {
            if (s != null && !s.isBlank()) {
                parts.add(s.strip());
            }
        }
        return String.join(" | ", parts);
    }

    /** "Priya_Sharma_Northwind_Cover_Letter". */
    static String fileBase(String person, String company) {
        String raw = ((person == null ? "" : person) + " " + (company == null ? "" : company)).trim();
        String safe = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
        return (safe.isEmpty() ? "" : (safe.length() > 80 ? safe.substring(0, 80) : safe) + "_") + "Cover_Letter";
    }
}
