package com.referboard.ingestion.normalize;

import com.referboard.ingestion.source.SourceType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A posting after cleaning, classification and de-dup keying; the unit the processor stores and publishes. */
public record NormalizedJob(
        UUID jobId,
        String dedupKey,
        SourceType sourceType,
        String sourceKey,
        String sourceJobId,
        String companyName,
        String companySlug,
        String title,
        String normalizedTitle,
        String location,
        String country,
        boolean remote,
        String employmentType,
        String seniority,
        String department,
        String descriptionHtml,
        String descriptionText,
        String applyUrl,
        Instant postedAt,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency,
        List<String> skills,
        String contentHash
) {
}
