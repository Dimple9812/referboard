package com.referboard.job.store;

import com.referboard.job.matching.MatchScorer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The user-independent part of a search result (cacheable). Skills are canonical names, in posting order. */
public record JobCardRow(
        UUID id,
        String title,
        String companyName,
        String location,
        boolean remote,
        String seniority,
        String employmentType,
        Instant postedAt,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency,
        List<String> skills,
        boolean ghostJobSuspected,
        String applyUrl,
        String source
) {
    public MatchScorer.JobFacts facts(Double cosine) {
        return new MatchScorer.JobFacts(title, location, remote, seniority, salaryMax, salaryCurrency, skills, cosine);
    }
}
