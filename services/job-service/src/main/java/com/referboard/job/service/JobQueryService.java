package com.referboard.job.service;

import com.referboard.common.web.ApiException;
import com.referboard.job.config.JobsProperties;
import com.referboard.job.matching.MatchScorer;
import com.referboard.job.store.JobCardRow;
import com.referboard.job.store.MatchStore;
import com.referboard.job.store.SearchCriteria;
import com.referboard.job.store.SearchRepository;
import com.referboard.job.web.dto.JobDtos.JobCard;
import com.referboard.job.web.dto.JobDtos.JobDetail;
import com.referboard.job.web.dto.JobDtos.MatchView;
import com.referboard.job.web.dto.JobDtos.SearchResponse;
import com.referboard.job.web.dto.JobDtos.Skill;
import com.referboard.skills.SkillTaxonomy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobQueryService {

    private final SearchRepository search;
    private final SearchCache cache;
    private final MatchingService matching;
    private final MatchStore matches;
    private final SkillTaxonomy taxonomy;
    private final JobsProperties props;

    public JobQueryService(SearchRepository search, SearchCache cache, MatchingService matching, MatchStore matches,
                           SkillTaxonomy taxonomy, JobsProperties props) {
        this.search = search;
        this.cache = cache;
        this.matching = matching;
        this.matches = matches;
        this.taxonomy = taxonomy;
        this.props = props;
    }

    public SearchResponse search(UUID userId, SearchCriteria criteria) {
        SearchCriteria c = normalise(criteria);
        SearchRepository.Page page = cache.getOrLoad(c, () -> search.search(c, userId));
        return toResponse(userId, page.items(), c.page(), c.size(), page.total());
    }

    /** Best precomputed matches first. */
    public SearchResponse recommended(UUID userId, int page, int size) {
        int s = clampSize(size);
        int p = Math.max(0, page);
        List<UUID> ids = matches.topJobIds(userId, p * s, s);
        Map<UUID, JobCardRow> byId = search.cards(ids).stream()
                .collect(Collectors.toMap(JobCardRow::id, Function.identity()));
        List<JobCardRow> ordered = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return toResponse(userId, ordered, p, s, matches.countOpen(userId));
    }

    public JobDetail detail(UUID userId, UUID jobId) {
        SearchRepository.JobDetailRow row = search.detail(jobId)
                .orElseThrow(() -> ApiException.notFound("Job", jobId));
        Map<UUID, MatchScorer.Breakdown> live = matching.live(userId, List.of(row.card()));
        MatchScorer.Breakdown b = live.get(jobId);
        MatchView match = b == null ? MatchView.unavailable("PROFILE_MISSING") : matchView(b);
        return new JobDetail(card(row.card(), b), row.descriptionHtml(), row.department(), row.country(), row.status(),
                row.closedAt(), row.firstSeenAt(), row.lastSeenAt(), match);
    }

    public MatchView match(UUID userId, UUID jobId) {
        return detail(userId, jobId).match();
    }

    private SearchResponse toResponse(UUID userId, List<JobCardRow> rows, int page, int size, long total) {
        Map<UUID, MatchScorer.Breakdown> live = matching.live(userId, rows);
        List<JobCard> items = rows.stream().map(r -> card(r, live.get(r.id()))).toList();
        return new SearchResponse(items, page, size, total, (long) (page + 1) * size < total);
    }

    private JobCard card(JobCardRow r, MatchScorer.Breakdown b) {
        return new JobCard(r.id(), r.title(), r.companyName(), r.location(), r.remote(), r.seniority(),
                r.employmentType(), r.postedAt(), r.salaryMin(), r.salaryMax(), r.salaryCurrency(), skills(r.skills()),
                r.ghostJobSuspected(), r.applyUrl(), r.source(), b == null ? null : b.matchPercent(),
                b == null ? List.of() : skills(b.matchedSkills()));
    }

    private MatchView matchView(MatchScorer.Breakdown b) {
        Map<String, Double> components = new LinkedHashMap<>();
        components.put("semantic", b.semantic());
        components.put("skills", b.skills());
        components.put("preferences", b.preferences());
        return new MatchView(true, null, b.matchPercent(), components, b.weights(), skills(b.matchedSkills()),
                skills(b.missingSkills()), b.reasons());
    }

    private List<Skill> skills(List<String> names) {
        return names.stream().map(n -> new Skill(n, taxonomy.display(n))).toList();
    }

    private SearchCriteria normalise(SearchCriteria c) {
        List<String> skills = c.skills() == null ? null : taxonomy.normalizeAll(c.skills());
        List<String> seniorities = c.seniorities() == null ? null
                : c.seniorities().stream().map(s -> s.trim().toUpperCase(java.util.Locale.ROOT)).distinct().sorted().toList();
        return new SearchCriteria(trimOrNull(c.q()), trimOrNull(c.role()), trimOrNull(c.location()), c.remote(),
                c.postedWithinDays(), trimOrNull(c.company()), seniorities, skills, c.hideGhostJobs(),
                c.sort() == null ? SearchCriteria.Sort.RELEVANCE : c.sort(), Math.max(0, c.page()), clampSize(c.size()));
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), props.maxPageSize());
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
