package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by ingestion-service for every new or changed posting. job-service upserts by {@code jobId}.
 *
 * <p>{@code jobId} is deterministic: {@code UUID.nameUUIDFromBytes(dedupKey)}, where dedupKey is
 * {@code lower(company) | normalizedTitle | normalizedLocation}. Re-ingesting the same posting from a
 * second source therefore updates the same row instead of creating a duplicate.
 *
 * @param contentHash  SHA-256 of the normalized content; job-service skips writes when unchanged
 * @param embedding    null when the content didn't change (flag-only update) or embedding failed: consumers keep any
 *                     vector they already hold for this job if {@code contentHash} matches, else store none
 * @param ghostJobSuspected true when the same dedup key has been listed for 60+ days since first seen
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobUpsertedEvent(
        EventMetadata metadata,
        UUID jobId,
        String dedupKey,
        String source,
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
        String departmentName,
        String descriptionHtml,
        String descriptionText,
        String applyUrl,
        Long salaryMin,
        Long salaryMax,
        String salaryCurrency,
        Instant postedAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        List<String> skills,
        float[] embedding,
        String embeddingModel,
        boolean ghostJobSuspected,
        String contentHash
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.JOB_UPSERTED;
    }

    @Override
    public String key() {
        return jobId.toString();
    }
}
