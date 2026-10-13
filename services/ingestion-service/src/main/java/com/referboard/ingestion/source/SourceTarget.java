package com.referboard.ingestion.source;

/**
 * One thing to fetch: an ATS board ({@code slug} = board token) or an aggregator query ({@code slug} = query text).
 *
 * @param sourceKey stable id, e.g. {@code greenhouse:razorpay}, {@code adzuna:in:data engineer}
 */
public record SourceTarget(String sourceKey, SourceType type, String slug, String displayName) {

    public static SourceTarget board(SourceType type, String slug, String displayName) {
        return new SourceTarget(type.name().toLowerCase() + ":" + slug, type, slug, displayName);
    }
}
