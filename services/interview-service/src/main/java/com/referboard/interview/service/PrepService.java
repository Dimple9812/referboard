package com.referboard.interview.service;

import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.web.ApiException;
import com.referboard.events.InterviewPrepCreatedEvent;
import com.referboard.interview.client.AnalyzerClient;
import com.referboard.interview.config.InterviewProperties;
import com.referboard.interview.domain.PrepItem;
import com.referboard.interview.domain.PrepPack;
import com.referboard.interview.prep.AiQuestions;
import com.referboard.interview.prep.PrepBuilder;
import com.referboard.interview.repository.PrepPackRepository;
import com.referboard.interview.web.dto.PrepDtos.ItemView;
import com.referboard.interview.web.dto.PrepDtos.PackDetail;
import com.referboard.interview.web.dto.PrepDtos.PackSummary;
import com.referboard.interview.web.dto.PrepDtos.UpdateItemRequest;
import com.referboard.interview.web.dto.PrepDtos.Usage;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Prep packs. Built from an analysis (fetched with the user's own token), rules first and AI questions on top when an
 * LLM is configured. Free users get {@code free-packs-per-month}; Pro is unlimited.
 */
@Service
public class PrepService {

    private static final Logger log = LoggerFactory.getLogger(PrepService.class);
    static final int MAX_PACKS = 300;

    private final AnalyzerClient analyzer;
    private final PrepBuilder builder;
    private final AiQuestions ai;
    private final PrepPackRepository packs;
    private final InterviewProperties props;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PrepService(AnalyzerClient analyzer, PrepBuilder builder, AiQuestions ai, PrepPackRepository packs,
                       InterviewProperties props, EventPublisher events, EventJson eventJson, TransactionTemplate tx,
                       Clock clock) {
        this.analyzer = analyzer;
        this.builder = builder;
        this.ai = ai;
        this.packs = packs;
        this.props = props;
        this.events = events;
        this.eventJson = eventJson;
        this.tx = tx;
        this.clock = clock;
    }

    public PackDetail create(UUID userId, boolean pro, UUID analysisId) {
        if (!pro && packs.countCreatedSince(userId, monthStart()) >= props.freePacksPerMonth()) {
            throw ApiException.tooManyRequests("PREP_LIMIT", "You've used this month's " + props.freePacksPerMonth()
                    + " free prep packs. They reset on " + nextMonth() + ", or go Pro for unlimited packs.");
        }
        AnalyzerClient.Context ctx = fetch(analysisId);
        List<PrepBuilder.Item> items = new ArrayList<>(builder.build(ctx));
        List<String> notices = new ArrayList<>();
        String origin = "RULES";
        String model = null;
        if (ai.available()) {
            try {
                List<PrepBuilder.Item> extra = ai.questions(ctx, items);
                if (!extra.isEmpty()) {
                    // AI questions go after the rule-based ones of the same section.
                    items.addAll(extra);
                    items.sort(Comparator.comparingInt(i -> i.section().ordinal()));
                    origin = "AI";
                    model = ai.modelId();
                }
            } catch (RuntimeException e) {
                log.warn("AI interview questions failed: {}", e.toString());
                notices.add("AI questions aren't available right now; these come from the posting and our question bank.");
            }
        } else {
            notices.add("These questions come from the posting and our question bank. With AI configured, you'd also "
                    + "get questions written for this specific job.");
        }

        String finalOrigin = origin;
        String finalModel = model;
        PrepPack saved = tx.execute(s -> {
            if (packs.countCreatedSince(userId, Instant.EPOCH) >= MAX_PACKS) {
                throw ApiException.conflict("PREP_PACK_LIMIT", "You have " + MAX_PACKS + " prep packs. Delete some first.");
            }
            PrepPack p = new PrepPack(UUID.randomUUID(), userId, analysisId);
            p.setJobId(ctx.job().jobId());
            p.setJobTitle(truncate(ctx.job().title(), 300));
            p.setCompanyName(truncate(ctx.job().companyName(), 200));
            p.setOrigin(finalOrigin);
            p.setModel(finalModel);
            int pos = 0;
            for (PrepBuilder.Item i : items) {
                PrepItem item = new PrepItem(UUID.randomUUID(), p, pos++);
                item.setSection(i.section().name());
                item.setQuestion(truncate(i.question(), 500));
                item.setChecks(truncate(i.checks(), 500));
                item.setTip(truncate(i.tip(), 800));
                item.setEvidence(truncate(i.evidence(), 1000));
                item.setEvidenceFrom(truncate(i.evidenceFrom(), 300));
                item.setSkill(truncate(i.skill(), 100));
                item.setOrigin(i.origin());
                p.getItems().add(item);
            }
            packs.saveAndFlush(p);
            events.publish(new InterviewPrepCreatedEvent(eventJson.newMetadata("InterviewPrepCreated"), userId, p.getId(),
                    p.getJobId(), items.size(), clock.instant()));
            return p;
        });
        log.info("Prep pack {} for user {}: {} questions ({})", saved.getId(), userId, items.size(), origin);
        return detail(saved, notices);
    }

    @Transactional(readOnly = true)
    public List<PackSummary> list(UUID userId, UUID jobId) {
        List<PrepPack> xs = jobId == null ? packs.findByUserIdOrderByCreatedAtDesc(userId)
                : packs.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
        return xs.stream().map(p -> new PackSummary(p.getId(), p.getAnalysisId(), p.getJobId(), p.getJobTitle(),
                p.getCompanyName(), p.getOrigin(), p.getCreatedAt(), p.getItems().size(),
                count(p, PrepItem.Confidence.PRACTISING), count(p, PrepItem.Confidence.CONFIDENT))).toList();
    }

    @Transactional(readOnly = true)
    public PackDetail get(UUID userId, UUID id) {
        return detail(find(userId, id), null);
    }

    @Transactional
    public ItemView updateItem(UUID userId, UUID packId, UUID itemId, UpdateItemRequest req) {
        PrepPack p = find(userId, packId);
        PrepItem item = p.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Question", itemId));
        if (req.version() != null && req.version() != item.getVersion()) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "This question changed in another tab. Reload to see it.");
        }
        if (req.notes() != null) {
            item.setNotes(req.notes().isBlank() ? null : req.notes().strip());
        }
        if (req.confidence() != null) {
            item.setConfidence(req.confidence());
        }
        item.setUpdatedAt(clock.instant());
        packs.flush();
        return view(item);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        find(userId, id).setDeletedAt(clock.instant());
    }

    @Transactional(readOnly = true)
    public Usage usage(UUID userId, boolean pro) {
        int used = (int) packs.countCreatedSince(userId, monthStart());
        String period = YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
        if (pro) {
            return new Usage(period, used, null, null, true, null);
        }
        int limit = props.freePacksPerMonth();
        return new Usage(period, used, limit, Math.max(0, limit - used), false, nextMonth().toString());
    }

    // ---------------------------------------------------------------------------------------------------------

    private AnalyzerClient.Context fetch(UUID analysisId) {
        try {
            return analyzer.context(analysisId);
        } catch (FeignException.NotFound e) {
            throw ApiException.notFound("Analysis", analysisId);
        } catch (FeignException e) {
            log.warn("Analyzer call failed: {}", e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                    "Couldn't read that analysis right now. Please try again in a moment.");
        }
    }

    private PrepPack find(UUID userId, UUID id) {
        return packs.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Prep pack", id));
    }

    private PackDetail detail(PrepPack p, List<String> notices) {
        return new PackDetail(p.getId(), p.getAnalysisId(), p.getJobId(), p.getJobTitle(), p.getCompanyName(),
                p.getOrigin(), p.getCreatedAt() == null ? clock.instant() : p.getCreatedAt(),
                p.getItems().stream().map(PrepService::view).toList(), notices);
    }

    private static ItemView view(PrepItem i) {
        return new ItemView(i.getId(), i.getSection(), i.getQuestion(), i.getChecks(), i.getTip(), i.getEvidence(),
                i.getEvidenceFrom(), i.getSkill(), i.getOrigin(), i.getNotes(), i.getConfidence().name(), i.getVersion());
    }

    private static int count(PrepPack p, PrepItem.Confidence c) {
        return (int) p.getItems().stream().filter(i -> i.getConfidence() == c).count();
    }

    private Instant monthStart() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private LocalDate nextMonth() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusMonths(1).atDay(1);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
