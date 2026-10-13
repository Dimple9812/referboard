package com.referboard.referral.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "referboard.referral")
public record ReferralProperties(
        @DefaultValue("P2D") Duration sendReferralAfter,
        @DefaultValue("P5D") Duration followUpAfter,
        @DefaultValue("300000") long reminderScanIntervalMs,
        @DefaultValue("25") int maxContactsPerJob,
        /** LLM drafts per user per UTC day (4 per intro request); beyond it, template drafts are returned. */
        @DefaultValue("120") int maxAiDraftsPerDay
) {
}
