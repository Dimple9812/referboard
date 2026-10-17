package com.referboard.analyzer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.analyzer.domain.InterviewPrep;
import com.referboard.analyzer.domain.JdAnalysis;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.prep.AiPrepWriter;
import com.referboard.analyzer.prep.PrepItem;
import com.referboard.analyzer.prep.PrepPlanner;
import com.referboard.analyzer.prep.QuestionBank;
import com.referboard.analyzer.repository.InterviewPrepRepository;
import com.referboard.analyzer.repository.PrepAnswers;
import com.referboard.analyzer.resume.VersionContent;
import com.referboard.analyzer.scoring.MatchScorer;
import com.referboard.analyzer.web.dto.AnalyzerDtos.AnalysisReport;
import com.referboard.analyzer.web.dto.PrepDtos.Confidence;
import com.referboard.analyzer.web.dto.PrepDtos.ItemView;
import com.referboard.analyzer.web.dto.PrepDtos.PrepResponse;
import com.referboard.analyzer.web.dto.PrepDtos.PrepSummary;
import com.referboard.analyzer.web.dto.PrepDtos.Progress;
import com.referboard.analyzer.web.dto.PrepDtos.SaveAnswerRequest;
import com.referboard.common.web.ApiException;
import com.referboard.skills.SkillTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interview prep plans from analyses (one per analysis; asking again returns the same plan with your notes), and the
 * user's practice notes and confidence per question.
 */
@Service
public class InterviewPrepService {

    private static final Logger log = LoggerFactory.getLogger(InterviewPrepService.class);
    static final int MAX_PREPS_PER_USER = 200;

    private final AnalysisService analyses;
    private final InterviewPrepRepository preps;
    private final PrepAnswers answers;
    private final VersionContent contentJson;
    private final MatchScorer scorer;
    private final AiPrepWriter ai;
    private final PrepPlanner planner;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InterviewPrepService(AnalysisService analyses, InterviewPrepRepository preps, PrepAnswers answers,
                                VersionContent contentJson, MatchScorer scorer, AiPrepWriter ai, SkillTaxonomy taxonomy,
                                ObjectMapper mapper, TransactionTemplate tx, Clock clock) {
        this.analyses = analyses;
        this.preps = preps;
        this.answers = answers;
        this.contentJson = contentJson;
        this.scorer = scorer;
        this.ai = ai;
        this.planner = new PrepPlanner(QuestionBank.load(), taxonomy);
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    public PrepResponse create(UUID userId, UUID analysisId) {
        JdAnalysis analysis = analyses.find(userId, analysisId);
        var existing = preps.findByAnalysisIdAndUserId(analysisId, userId);
        if (existing.isPresent()) {
            return view(existing.get(), null, true);
        }
        if (preps.countByUserId(userId) >= MAX_PREPS_PER_USER) {
            throw ApiException.conflict("PREP_LIMIT", "You have " + MAX_PREPS_PER_USER
                    + " prep plans. Delete some to make more.");
        }
        AnalysisReport report = analyses.read(analysis);
        ResumeContent resume = contentJson.read(analysis.getResumeContent());
        List<PrepItem> items = new ArrayList<>(planner.plan(resume, report.parsedJd(), report.matchedSkills(),
                report.missingSkills(), scorer.years(resume), report.job().title(), report.job().companyName()));

        List<String> notices = new ArrayList<>();
        boolean aiUsed = false;
        if (ai.available()) {
            try {
                List<PrepItem> extra = ai.questions(report.parsedJd(), analysis.getJdText(), report.job().title(),
                        report.job().companyName(), items);
                if (!extra.isEmpty()) {
                    // Role questions from the model go right after the posting's own responsibilities.
                    int at = lastIndexOf(items, PrepItem.Section.ROLE, PrepItem.Section.TECHNICAL, PrepItem.Section.EXPERIENCE);
                    items.addAll(at + 1, extra);
                    aiUsed = true;
                }
            } catch (RuntimeException e) {
                log.warn("AI prep questions failed: {}", e.toString());
                notices.add("Role-specific AI questions are unavailable right now; the plan uses the posting and your resume.");
            }
        }

        boolean finalAi = aiUsed;
        try {
            InterviewPrep saved = tx.execute(s -> {
                InterviewPrep p = new InterviewPrep(UUID.randomUUID(), userId);
                p.setAnalysisId(analysis.getId());
                p.setJobId(analysis.getJobId());
                p.setJobTitle(report.job().title());
                p.setCompanyName(report.job().companyName());
                p.setItems(json(items));
                p.setAiQuestions(finalAi);
                return preps.saveAndFlush(p);
            });
            return view(saved, notices, false);
        } catch (DataIntegrityViolationException race) {
            // Two tabs asked at once: the other one won; return its plan.
            return view(preps.findByAnalysisIdAndUserId(analysisId, userId).orElseThrow(() -> race), null, true);
        }
    }

    @Transactional(readOnly = true)
    public PrepResponse get(UUID userId, UUID id) {
        return view(find(userId, id), null, false);
    }

    @Transactional(readOnly = true)
    public List<PrepSummary> list(UUID userId, UUID jobId) {
        List<InterviewPrep> xs = jobId == null ? preps.findByUserIdOrderByCreatedAtDesc(userId)
                : preps.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
        return xs.stream().map(p -> {
            PrepResponse v = view(p, null, false);
            return new PrepSummary(p.getId(), p.getAnalysisId(), p.getJobId(), p.getJobTitle(), p.getCompanyName(),
                    v.createdAt(), v.progress());
        }).toList();
    }

    @Transactional
    public ItemView saveAnswer(UUID userId, UUID prepId, String itemId, SaveAnswerRequest req) {
        InterviewPrep p = find(userId, prepId);
        PrepItem item = items(p).stream().filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Prep question", itemId));
        String notes = req.notes() == null || req.notes().isBlank() ? null : req.notes().strip().replace("\r\n", "\n");
        PrepAnswers.Answer a = answers.save(prepId, itemId, notes, req.confidence().name(), clock.instant());
        return toView(item, a);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        find(userId, id).setDeletedAt(clock.instant());
    }

    private InterviewPrep find(UUID userId, UUID id) {
        return preps.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Interview prep", id));
    }

    private PrepResponse view(InterviewPrep p, List<String> notices, boolean existing) {
        Map<String, PrepAnswers.Answer> byItem = answers.forPrep(p.getId()).stream()
                .collect(Collectors.toMap(PrepAnswers.Answer::itemId, Function.identity()));
        List<ItemView> views = items(p).stream().map(i -> toView(i, byItem.get(i.id()))).toList();
        // Questions to ask the interviewer aren't practised, so they don't count toward progress.
        List<ItemView> practised = views.stream().filter(v -> v.section() != PrepItem.Section.ASK).toList();
        int ready = (int) practised.stream().filter(v -> v.confidence() == Confidence.READY).count();
        int shaky = (int) practised.stream().filter(v -> v.confidence() == Confidence.SHAKY).count();
        Progress progress = new Progress(practised.size(), ready, shaky, practised.size() - ready - shaky);
        return new PrepResponse(p.getId(), p.getAnalysisId(), p.getJobId(), p.getJobTitle(), p.getCompanyName(),
                p.getCreatedAt() == null ? clock.instant() : p.getCreatedAt(), p.isAiQuestions(), progress, views,
                notices, existing);
    }

    private static ItemView toView(PrepItem i, PrepAnswers.Answer a) {
        return new ItemView(i.id(), i.section(), i.question(), i.why(), i.pointers(), i.example(), i.skill(), i.source(),
                a == null ? null : a.notes(), a == null ? Confidence.NEW : Confidence.valueOf(a.confidence()),
                a == null ? null : a.updatedAt());
    }

    private List<PrepItem> items(InterviewPrep p) {
        try {
            return mapper.readValue(p.getItems(), new TypeReference<List<PrepItem>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable prep plan " + p.getId(), e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int lastIndexOf(List<PrepItem> items, PrepItem.Section... sections) {
        for (PrepItem.Section s : sections) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).section() == s) {
                    return i;
                }
            }
        }
        return -1;
    }
}
