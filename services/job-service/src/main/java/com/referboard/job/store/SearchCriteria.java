package com.referboard.job.store;

import java.util.List;

/**
 * Normalised job search filters. Every field is optional.
 *
 * @param q                 free text over title, company and description (web-search syntax: quotes, -exclude, OR)
 * @param role              fuzzy role/title match, e.g. "backend engineer"
 * @param location          city (any spelling: "Bangalore" finds "Bengaluru")
 * @param remote            true = remote only, false = on-site/hybrid only
 * @param postedWithinDays  listed in the last N days
 * @param company           company name contains
 * @param seniorities       INTERN, ENTRY, MID, SENIOR, LEAD, PRINCIPAL
 * @param skills            must mention at least one of these (canonical names)
 * @param hideGhostJobs     exclude postings listed for 60+ days
 */
public record SearchCriteria(
        String q,
        String role,
        String location,
        Boolean remote,
        Integer postedWithinDays,
        String company,
        List<String> seniorities,
        List<String> skills,
        boolean hideGhostJobs,
        Sort sort,
        int page,
        int size
) {
    public enum Sort { RELEVANCE, RECENT, MATCH }

    /** Effective sort: relevance needs a query; otherwise newest first. */
    public Sort effectiveSort() {
        if (sort == Sort.MATCH) {
            return Sort.MATCH;
        }
        return (sort == Sort.RELEVANCE || sort == null) && q != null && !q.isBlank() ? Sort.RELEVANCE : Sort.RECENT;
    }
}
