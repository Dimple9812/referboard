package com.referboard.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Indian city name aliases ("Bangalore" = "Bengaluru", "Gurgaon" = "Gurugram"), shared by job search filters and
 * match scoring so a user who prefers "Bangalore" matches jobs posted in "Bengaluru, Karnataka".
 */
public final class Cities {

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        put("bengaluru", "bangalore", "blr");
        put("mumbai", "bombay", "navi mumbai", "thane");
        put("delhi", "new delhi", "delhi ncr", "ncr");
        put("gurugram", "gurgaon");
        put("noida", "greater noida");
        put("hyderabad", "secunderabad");
        put("chennai", "madras");
        put("pune");
        put("kolkata", "calcutta");
        put("ahmedabad", "gandhinagar");
        put("jaipur");
        put("kochi", "cochin");
        put("thiruvananthapuram", "trivandrum");
        put("chandigarh", "mohali");
        put("indore");
        put("coimbatore");
        put("mysuru", "mysore");
        put("vadodara", "baroda");
        put("visakhapatnam", "vizag");
    }

    private Cities() {
    }

    private static void put(String canonical, String... aliases) {
        ALIASES.put(canonical, canonical);
        for (String a : aliases) {
            ALIASES.put(a, canonical);
        }
    }

    /** Canonical city for a known name/alias, else the trimmed lowercase input. */
    public static String canonical(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, key);
    }

    /** Every spelling of the same city, for building search filters: "bangalore" → [bengaluru, bangalore, blr]. */
    public static List<String> spellings(String name) {
        String canonical = canonical(name);
        Set<String> all = ALIASES.entrySet().stream().filter(e -> e.getValue().equals(canonical))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        all.add(canonical);
        return List.copyOf(all);
    }

    /** True if {@code location} text mentions {@code city} under any of its spellings. */
    public static boolean mentions(String location, String city) {
        if (location == null || city == null || city.isBlank()) {
            return false;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        return spellings(city).stream().anyMatch(s -> lower.matches(".*\\b" + java.util.regex.Pattern.quote(s) + "\\b.*"));
    }
}
