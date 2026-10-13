package com.referboard.ingestion.normalize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns free-form location strings ("Bangalore, KA, India / Remote", "Remote - US") into: a display string, a
 * canonical key for de-duplication, a country, and whether the role is remote — and if remote, whether it's open
 * to candidates in India.
 */
public final class LocationNormalizer {

    public record Location(String display, String key, String country, boolean remote, boolean remoteOpenToIndia) {
    }

    /** alias → canonical city. Order matters only for display preference. */
    private static final Map<String, String> CITIES = new LinkedHashMap<>();

    static {
        put("bengaluru", "bengaluru", "bangalore", "blr");
        put("mumbai", "mumbai", "bombay", "navi mumbai", "thane");
        put("delhi", "delhi", "new delhi", "delhi ncr", "ncr");
        put("gurugram", "gurugram", "gurgaon");
        put("noida", "noida", "greater noida");
        put("hyderabad", "hyderabad", "secunderabad");
        put("chennai", "chennai", "madras");
        put("pune", "pune");
        put("kolkata", "kolkata", "calcutta");
        put("ahmedabad", "ahmedabad", "gandhinagar");
        put("jaipur", "jaipur");
        put("kochi", "kochi", "cochin");
        put("thiruvananthapuram", "thiruvananthapuram", "trivandrum");
        put("chandigarh", "chandigarh", "mohali");
        put("indore", "indore");
        put("coimbatore", "coimbatore");
        put("mysuru", "mysuru", "mysore");
        put("bhubaneswar", "bhubaneswar");
        put("nagpur", "nagpur");
        put("vadodara", "vadodara", "baroda");
        put("surat", "surat");
        put("lucknow", "lucknow");
        put("visakhapatnam", "visakhapatnam", "vizag");
        put("goa", "goa", "panaji");
    }

    private record CityPattern(String alias, String canonical, Pattern pattern) {
    }

    private static final List<CityPattern> CITY_PATTERNS = CITIES.entrySet().stream()
            .map(e -> new CityPattern(e.getKey(), e.getValue(),
                    Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b")))
            .toList();

    private static final List<String> INDIAN_STATES = List.of("karnataka", "maharashtra", "telangana", "haryana",
            "tamil nadu", "uttar pradesh", "kerala", "west bengal", "gujarat", "rajasthan", "andhra pradesh",
            "odisha", "punjab", "madhya pradesh");

    private static final Pattern REMOTE = Pattern.compile(
            "(?i)\\b(remote|work from home|wfh|anywhere|distributed|fully[- ]remote)\\b");
    private static final Pattern INDIA_OR_GLOBAL = Pattern.compile(
            "(?i)\\b(india|indian|apac|asia|asia[- ]pacific|global|globally|worldwide|anywhere|international)\\b");
    private static final Pattern OTHER_REGION = Pattern.compile("(?i)\\b(us|usa|u\\.s\\.|united states|canada|"
            + "uk|u\\.k\\.|united kingdom|europe|eu|emea|latam|americas|north america|germany|france|spain|"
            + "netherlands|poland|ireland|portugal|australia|new zealand|singapore|japan|brazil|mexico|"
            + "[a-z]{2,} time ?zones?|est|pst|cet)\\b");

    private LocationNormalizer() {
    }

    private static void put(String canonical, String... aliases) {
        for (String alias : aliases) {
            CITIES.put(alias, canonical);
        }
    }

    public static Location normalize(String raw, Boolean remoteFlag, String countryHint) {
        String text = raw == null ? "" : raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        // The city mentioned first wins ("Gurugram, Delhi NCR" is Gurugram); longer aliases win ties.
        String city = null;
        int bestPos = Integer.MAX_VALUE;
        int bestLen = 0;
        for (CityPattern c : CITY_PATTERNS) {
            var m = c.pattern().matcher(lower);
            if (m.find() && (m.start() < bestPos || (m.start() == bestPos && c.alias().length() > bestLen))) {
                city = c.canonical();
                bestPos = m.start();
                bestLen = c.alias().length();
            }
        }
        boolean mentionsIndia = city != null || lower.contains("india")
                || INDIAN_STATES.stream().anyMatch(lower::contains);
        boolean remote = Boolean.TRUE.equals(remoteFlag) || REMOTE.matcher(lower).find();

        String country = mentionsIndia ? "IN" : normalizeCountry(countryHint);
        boolean openToIndia = false;
        if (remote) {
            boolean otherRegion = OTHER_REGION.matcher(lower).find();
            boolean hintedElsewhere = country != null && !"IN".equals(country);
            openToIndia = mentionsIndia || INDIA_OR_GLOBAL.matcher(lower).find() || (!otherRegion && !hintedElsewhere);
        }

        String key;
        if (city != null) {
            key = remote ? city + "|remote" : city;
        } else if (remote) {
            key = mentionsIndia ? "remote|in" : openToIndia ? "remote" : "remote|" + (country == null ? "other" : country.toLowerCase(Locale.ROOT));
        } else {
            key = lower.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        }
        String display = text.isEmpty() ? (remote ? "Remote" : null) : text.replaceAll("\\s+", " ");
        return new Location(display, key, country, remote, openToIndia);
    }

    private static String normalizeCountry(String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        String h = hint.trim().toUpperCase(Locale.ROOT);
        return switch (h) {
            case "INDIA", "IND" -> "IN";
            case "USA", "UNITED STATES", "US" -> "US";
            case "UNITED KINGDOM", "UK", "GB" -> "GB";
            default -> h.length() == 2 ? h : null;
        };
    }
}
