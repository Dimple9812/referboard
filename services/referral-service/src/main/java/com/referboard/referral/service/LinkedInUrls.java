package com.referboard.referral.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds LinkedIn people-search links for the user to open in their own browser, and validates profile URLs the
 * user pastes. ReferBoard never requests linkedin.com: these are strings handed to the user, nothing more.
 */
public final class LinkedInUrls {

    private static final String SEARCH = "https://www.linkedin.com/search/results/people/?keywords=%s&origin=GLOBAL_SEARCH_HEADER";

    private LinkedInUrls() {
    }

    public static String peopleSearch(String keywords) {
        String q = keywords.replaceAll("\\s+", " ").trim();
        return SEARCH.formatted(URLEncoder.encode(q, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    /**
     * Normalises a pasted profile URL: https only, no query/fragment, trailing slash removed. LinkedIn URLs must be
     * member profiles ({@code /in/...}); other https URLs (a personal site, GitHub) are accepted as-is.
     *
     * @return empty when the URL is unusable
     */
    public static Optional<String> normalizeProfileUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (!s.matches("(?i)^https?://.*")) {
            s = "https://" + s;
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || !host.contains(".")) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
        boolean linkedIn = host.equals("linkedin.com") || host.endsWith(".linkedin.com");
        if (linkedIn) {
            if (!path.matches("(?i)/in/[^/]{2,100}")) {
                return Optional.empty();
            }
            host = "www.linkedin.com";
        }
        return Optional.of("https://" + host + path);
    }
}
