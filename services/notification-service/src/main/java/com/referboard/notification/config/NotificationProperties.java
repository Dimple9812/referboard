package com.referboard.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param appBaseUrl  the frontend, for links in emails and notifications
 * @param apiBaseUrl  the public API (gateway) base, for one-click unsubscribe links
 */
@ConfigurationProperties(prefix = "referboard.notification")
public record NotificationProperties(
        @DefaultValue("http://localhost:5173") String appBaseUrl,
        @DefaultValue("http://localhost:8080") String apiBaseUrl,
        @DefaultValue("ReferBoard <noreply@referboard.local>") String mailFrom,
        @DefaultValue("20") int maxEmailsPerUserPerDay,
        @DefaultValue("10000") long senderIntervalMs,
        @DefaultValue("5") int maxAttempts
) {
    public String appLink(String path) {
        return trimSlash(appBaseUrl) + path;
    }

    public String apiLink(String path) {
        return trimSlash(apiBaseUrl) + path;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
