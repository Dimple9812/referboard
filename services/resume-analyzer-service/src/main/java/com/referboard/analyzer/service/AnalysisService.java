package com.referboard.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.ai.EmbeddingClient;
import com.referboard.analyzer.client.JobClient;
import com.referboard.analyzer.config.AnalyzerProperties;
import com.referboard.analyzer.domain.JdAnalysis;
import com.referboard.analyzer.jd.JdParserService;
import com.referboard.analyzer.jd.JdText;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.repository.JdAnalysisRepository;
import com.referboard.analyzer.repository.UsageCounters;
import com.referboard.analyzer.resume.LoadedResume;
import com.referboard.analyzer.resume.ResumeLoader;
import com.referboard.analyzer.resume.VersionContent;
import com.referboard.analyzer.scoring.CachingEmbeddings;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.analyzer.scoring.MatchScorer;
import com.referboard.analyzer.suggest.ResumeEditor;
import com.referboard.analyzer.suggest.Suggestion;
import com.referboard.analyzer.suggest.SuggestionEngine;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisPage;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisReport;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisSummary;
import com.referboard.analyzer.web.dto.AnalyzerDtos.JobRef;
import com.referboard.analyzer.web.dto.AnalyzerDtos.ResumeRef;
import com.referboard.analyzer.web.dto.AnalyzerDtos.Usage;
import com.referboard.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Runs a JD Match analysis: resolve the JD (text, file or job), load the chosen resume, parse the JD (cached),
 * score, build suggestions, measure each suggestion's impact by re-scoring, store the report.
 *
 * <p>Free users get {@code free-analyses-per-month}; an identical analysis (same JD and resume) within 24 hours is
 * returned again without counting.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    static final Duration REUSE_WINDOW = Duration.ofHours(24);

    public record JdInput(String text, String source, UUID jobId, String titleHint, String companyHint) {
    }

    private final JdParserService jdParser;
    private final ResumeLoader resumes;
    private final MatchScorer scorer;
    private final SuggestionEngine suggestions;
    private final EmbeddingClient embeddings;
    private final JdAnalysisRepository analyses;
    private final UsageCounters usage;
    private final JobClient jobs;
    private final VersionContent contentJson;
    private final AnalyzerProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AnalysisService(JdParserService jdParser, ResumeLoader resumes, MatchScorer scorer,
                           SuggestionEngine suggestions, EmbeddingClient embeddings, JdAnalysisRepository analyses,
                           UsageCounters usage, JobClient jobs, VersionContent contentJson, AnalyzerProperties props,
                           ObjectMapper mapper, Clock clock) {
        this.jdParser = jdParser;
        this.resumes = resumes;
        this.scorer = scorer;
        this.suggestions = suggestions;
        this.embeddings = embeddings;
        this.analyses = analyses;
        this.usage = usage;
        this.jobs = jobs;
        this.contentJson = contentJson;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** JD from a ReferBoard job (its description), optionally overridden by pasted text. */
    public JdInput fromJob(UUID jobId, String pastedText) {
        JobClient.JobDetail detail;
        try {
            detail = Upstream.call(() -> jobs.job(jobId));
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                throw ApiException.notFound("Job", jobId);
            }
            throw e;
        }
        String text = pastedText != null && !pastedText.isBlank() ? pastedText : JdText.fromHtml(detail.descriptionHtml());
        return new JdInput(text, pastedText != null && !pastedText.isBlank() ? "TEXT" : "JOB", jobId,
                detail.job().title(), detail.job().companyName());
    }

    public AnalysisReport analyze(UUID userId, boolean unlimited, JdInput jd, LoadedResume.Source resumeSource,
                                  UUID resumeId) {
        String text = JdText.normalize(jd.text());
        if (text.length() < props.minJdChars()) {
            throw ApiException.badRequest("JD_TOO_SHORT", "That job description is too short to analyse. Paste the "
                    + "full posting (at least " + props.minJdChars() + " characters).");
        }
        if (text.length() > props.maxJdChars()) {
            throw ApiException.badRequest("JD_TOO_LONG", "That's longer than a job description should be (max "
                    + props.maxJdChars() + " characters).");
        }
        String hash = JdText.hash(text);
        LoadedResume resume = resumes.load(userId, resumeSource == null ? LoadedResume.Source.PROFILE_RESUME
                : resumeSource, resumeId);

        var recent = analyses.findFirstByUserIdAndJdHashAndResumeRefIdAndCreatedAtAfterOrderByCreatedAtDesc(
                userId, hash, resume.refId(), clock.instant().minus(REUSE_WINDOW));
        if (recent.isPresent()) {
            return read(recent.get()).asReused();
        }

        String period = period();
        if (unlimited) {
            usage.increment(userId, period);
        } else if (usage.tryIncrement(userId, period, props.freeAnalysesPerMonth()) < 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ANALYSIS_LIMIT", "You've used your "
                    + props.freeAnalysesPerMonth() + " free analyses this month. They reset on " + resetsOn() + ".");
        }
        try {
            return run(userId, jd, text, hash, resume);
        } catch (RuntimeException e) {
            usage.decrement(userId, period);   // a failed analysis doesn't use up the allowance
            throw e;
        }
    }

    private AnalysisReport run(UUID userId, JdInput jd, String text, String hash, LoadedResume resume) {
        JdParserService.Result parsed = jdParser.parse(text, hash, jd.titleHint(), jd.companyHint());
        ParsedJd pjd = parsed.parsed();
        CachingEmbeddings emb = new CachingEmbeddings(embeddings);
        ResumeContent content = resume.content();

        MatchReport report = scorer.score(content, resume.text(), resume.pageCount(), pjd, emb);
        BigDecimal years = scorer.years(content);
        SuggestionEngine.Output out = suggestions.suggest(content, pjd, report, years);

        // Impact = change in score when only that suggestion is applied, scored on the rendered resume text so
        // before and after are compared like for like.
        int base = scorer.score(content, content.toPlainText(), null, pjd, emb).score();
        List<Suggestion> withImpact = new ArrayList<>();
        for (Suggestion s : out.suggestions()) {
            if (!s.applicable()) {
                withImpact.add(s);
                continue;
            }
            ResumeContent applied = ResumeEditor.apply(content, List.of(s));
            int delta = scorer.score(applied, applied.toPlainText(), null, pjd, emb).score() - base;
            if (delta < 0) {
                // e.g. a summary rewrite that drops terms the old one had: never recommend making the match worse
                log.debug("Dropping {} suggestion {} with negative impact {}", s.type(), s.id(), delta);
                continue;
            }
            Suggestion scored = s.withImpact(delta);
            if (delta >= 4 && s.priority() != Suggestion.Priority.HIGH) {
                scored = scored.withPriority(Suggestion.Priority.HIGH);
            }
            withImpact.add(scored);
        }
        withImpact.sort(Comparator.comparing(Suggestion::priority)
                .thenComparing(s -> s.applicable() ? 0 : 1)
                .thenComparing(s -> s.impact() == null ? 0 : -s.impact()));

        List<Suggestion> safe = withImpact.stream().filter(s -> s.applicable() && !s.requiresConfirmation()).toList();
        List<Suggestion> all = withImpact.stream().filter(Suggestion::applicable).toList();
        int projected = project(report.score(), base, content, safe, pjd, emb);
        int projectedAll = project(report.score(), base, content, all, pjd, emb);

        List<String> notices = new ArrayList<>();
        if (parsed.parser() == JdParserService.Parser.HEURISTIC) {
            notices.add("The posting was read without AI: requirements come from its headings and our skills list, "
                    + "so unusual wording may be missed.");
        }
        notices.addAll(out.notices());
        if (resume.source() == LoadedResume.Source.PROFILE_RESUME && resume.pageCount() == null
                && resume.contentType() != null && resume.contentType().contains("pdf")) {
            notices.add("Page count unavailable for this file; length was judged by word count.");
        }

        UUID id = UUID.randomUUID();
        AnalysisReport result = new AnalysisReport(id, clock.instant(),
                new JobRef(jd.jobId(), pjd.title(), pjd.company(), jd.source()),
                new ResumeRef(resume.source(), resume.refId(), resume.name()),
                report.score(), report.verdict(), report.breakdown(), report.matchedSkills(), report.missingSkills(),
                report.atsChecks(), withImpact, projected, Math.max(projected, projectedAll), pjd,
                parsed.parser().name(), out.aiUsed(), notices, false);
        save(userId, result, jd, hash, text, content);
        log.info("Analysis {} for user {}: score {} ({}), {} suggestions, parser {}", id, userId, report.score(),
                report.verdict(), withImpact.size(), parsed.parser());
        return result;
    }

    private int project(int current, int base, ResumeContent content, List<Suggestion> accepted, ParsedJd jd,
                        EmbeddingClient emb) {
        if (accepted.isEmpty()) {
            return current;
        }
        ResumeContent applied = ResumeEditor.apply(content, accepted);
        int after = scorer.score(applied, applied.toPlainText(), null, jd, emb).score();
        return Math.max(0, Math.min(100, current + Math.max(0, after - base)));
    }

    /** Single insert (repository save is transactional on its own). */
    private void save(UUID userId, AnalysisReport r, JdInput jd, String hash, String text, ResumeContent content) {
        JdAnalysis a = new JdAnalysis(r.id(), userId);
        a.setJobId(jd.jobId());
        a.setJobTitle(truncate(r.job().title(), 300));
        a.setCompanyName(truncate(r.job().companyName(), 200));
        a.setJdSource(jd.source());
        a.setJdHash(hash);
        a.setJdText(text);
        a.setResumeSource(r.resume().source().name());
        a.setResumeRefId(r.resume().id());
        a.setResumeName(truncate(r.resume().name(), 300));
        a.setScore((short) r.score());
        a.setVerdict(r.verdict().name());
        a.setProjectedScore((short) r.projectedScore());
        a.setReport(json(r));
        a.setResumeContent(contentJson.write(content));
        analyses.save(a);
    }

    @Transactional(readOnly = true)
    public AnalysisReport get(UUID userId, UUID id) {
        return read(find(userId, id));
    }

    @Transactional(readOnly = true)
    public AnalysisPage list(UUID userId, UUID jobId, int page, int size) {
        PageRequest pr = PageRequest.of(page, size);
        Page<JdAnalysis> result = jobId == null ? analyses.findByUserIdOrderByCreatedAtDesc(userId, pr)
                : analyses.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId, pr);
        return new AnalysisPage(result.getContent().stream().map(a -> new AnalysisSummary(a.getId(), a.getCreatedAt(),
                        a.getJobId(), a.getJobTitle(), a.getCompanyName(), a.getScore(),
                        MatchReport.Verdict.valueOf(a.getVerdict()), a.getProjectedScore(), a.getResumeName())).toList(),
                page, size, result.getTotalElements(), result.hasNext());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        find(userId, id).setDeletedAt(clock.instant());
    }

    public Usage usage(UUID userId, boolean unlimited) {
        int used = usage.used(userId, period());
        return unlimited ? new Usage(period(), used, null, null, true, null)
                : new Usage(period(), used, props.freeAnalysesPerMonth(),
                Math.max(0, props.freeAnalysesPerMonth() - used), false, resetsOn());
    }

    JdAnalysis find(UUID userId, UUID id) {
        return analyses.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Analysis", id));
    }

    AnalysisReport read(JdAnalysis a) {
        try {
            return mapper.readValue(a.getReport(), AnalysisReport.class).withCreatedAt(a.getCreatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt analysis report " + a.getId(), e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String period() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    private String resetsOn() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusMonths(1).atDay(1).toString();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
