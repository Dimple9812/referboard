package com.referboard.ingestion.source;

import java.time.Instant;

/**
 * A posting as read from one source, before normalisation. Fields a source doesn't provide are null.
 *
 * @param remote        explicit remote flag from the source (Lever workplaceType, Ashby isRemote); null = unknown
 * @param countryHint   ISO code if the source states one, else null
 * @param descriptionHtml sanitised later; never rendered as-is
 */
public record RawPosting(
        String source,
        String sourceKey,
        String sourceJobId,
        String companyName,
        String companySlug,
        String title,
        String locationText,
        Boolean remote,
        String countryHint,
        String employmentType,
        String department,
        String descriptionHtml,
        String descriptionText,
        String applyUrl,
        Instant postedAt,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency
) {
}
