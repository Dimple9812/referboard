package com.referboard.job.matching;

import com.referboard.skills.Cities;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Transparent match score (0–100) between one user and one job. Three components, each in [0, 1]:
 *
 * <ul>
 *   <li><b>semantic</b> (45%) — cosine similarity of profile and job embeddings (same model only), rescaled</li>
 *   <li><b>skills</b> (35%) — share of the job's key skills the user has</li>
 *   <li><b>preferences</b> (20%) — target role, location/remote, seniority, salary floor</li>
 * </ul>
 * A component that can't be computed (no embedding, job lists no skills, user set no preferences) is left out and
 * the remaining weights are renormalised, so a missing signal never drags the score down by itself. The breakdown
 * returned is exactly what the UI shows.
 */
public final class MatchScorer {

    public static final double W_SEMANTIC = 0.45;
    public static final double W_SKILLS = 0.35;
    public static final double W_PREFERENCES = 0.20;

    /** Skills beyond the first 8 a job lists rarely decide shortlisting; cap the denominator. */
    private static final int KEY_SKILLS = 8;

    private static final List<String> LEVELS = List.of("INTERN", "ENTRY", "MID", "SENIOR", "LEAD", "PRINCIPAL");
    private static final Set<String> TITLE_STOPWORDS = Set.of("engineer", "developer", "i", "ii", "iii", "the", "and",
            "of", "senior", "junior", "lead", "staff", "sr", "jr");

    private MatchScorer() {
    }

    public record JobFacts(String title, String location, boolean remote, String seniority, Long salaryMax,
                           String salaryCurrency, List<String> skills, Double cosineSimilarity) {
    }

    public record Preferences(List<String> roles, List<String> locations, boolean remoteOnly, boolean openToRemote,
                              Long minSalaryInr, String experienceLevel) {
        public static final Preferences NONE = new Preferences(List.of(), List.of(), false, true, null, null);
    }

    public record UserFacts(List<String> skills, BigDecimal experienceYears, Preferences preferences) {
    }

    public record Breakdown(int matchPercent, Double semantic, Double skills, Double preferences,
                            List<String> matchedSkills, List<String> missingSkills, List<String> reasons,
                            Map<String, Double> weights) {
    }

    public static Breakdown score(UserFacts user, JobFacts job) {
        List<String> reasons = new ArrayList<>();

        // --- semantic ---
        Double semantic = job.cosineSimilarity() == null ? null : rescaleCosine(job.cosineSimilarity());

        // --- skills ---
        Set<String> userSkills = new LinkedHashSet<>(user.skills());
        List<String> matched = job.skills().stream().filter(userSkills::contains).toList();
        List<String> missing = job.skills().stream().filter(s -> !userSkills.contains(s)).limit(10).toList();
        Double skills = null;
        if (!job.skills().isEmpty()) {
            int denominator = Math.min(job.skills().size(), KEY_SKILLS);
            skills = Math.min(1.0, (double) matched.size() / denominator);
            reasons.add("You have %d of the %d skills this job asks for".formatted(matched.size(), job.skills().size()));
        }

        // --- preferences ---
        Double preferences = preferenceFit(user, job, reasons);

        // --- combine with renormalised weights ---
        Map<String, Double> weights = new LinkedHashMap<>();
        if (semantic != null) {
            weights.put("semantic", W_SEMANTIC);
        }
        if (skills != null) {
            weights.put("skills", W_SKILLS);
        }
        if (preferences != null) {
            weights.put("preferences", W_PREFERENCES);
        }
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        int percent = 0;
        if (total > 0) {
            double sum = 0;
            if (semantic != null) {
                sum += W_SEMANTIC * semantic;
            }
            if (skills != null) {
                sum += W_SKILLS * skills;
            }
            if (preferences != null) {
                sum += W_PREFERENCES * preferences;
            }
            percent = (int) Math.round(100 * sum / total);
            weights.replaceAll((k, v) -> round(v / total));
        }
        return new Breakdown(percent, round(semantic), round(skills), round(preferences), matched, missing,
                List.copyOf(reasons), Map.copyOf(weights));
    }

    /**
     * Embedding cosine similarities between a profile and an unrelated job sit around 0.1–0.2; strong fits reach
     * 0.6–0.7. Map [0.15, 0.70] onto [0, 1] so the component uses its full range.
     */
    static double rescaleCosine(double cos) {
        return clamp((cos - 0.15) / 0.55);
    }

    private static Double preferenceFit(UserFacts user, JobFacts job, List<String> reasons) {
        Preferences p = user.preferences() == null ? Preferences.NONE : user.preferences();
        List<Double> parts = new ArrayList<>();

        if (!p.roles().isEmpty()) {
            double role = p.roles().stream().mapToDouble(r -> roleOverlap(r, job.title())).max().orElse(0);
            parts.add(role);
            if (role >= 0.99) {
                reasons.add("Title matches a role you're targeting");
            }
        }

        if (p.remoteOnly()) {
            parts.add(job.remote() ? 1.0 : 0.0);
            reasons.add(job.remote() ? "Remote, as you prefer" : "Not remote (you prefer remote only)");
        } else if (job.remote() && p.openToRemote()) {
            parts.add(1.0);
            reasons.add("Remote role");
        } else if (!p.locations().isEmpty()) {
            boolean inPreferred = p.locations().stream().anyMatch(l -> Cities.mentions(job.location(), l));
            parts.add(inPreferred ? 1.0 : 0.2);
            reasons.add(inPreferred ? "In a location you prefer" : "Outside your preferred locations");
        }

        String userLevel = p.experienceLevel() != null ? p.experienceLevel() : levelFromYears(user.experienceYears());
        if (userLevel != null && job.seniority() != null && LEVELS.contains(job.seniority())) {
            int diff = Math.abs(LEVELS.indexOf(job.seniority()) - LEVELS.indexOf(userLevel));
            parts.add(switch (diff) {
                case 0 -> 1.0;
                case 1 -> 0.6;
                case 2 -> 0.25;
                default -> 0.0;
            });
            if (diff >= 2) {
                reasons.add("Seniority gap: role is %s, you're closer to %s".formatted(
                        job.seniority().toLowerCase(Locale.ROOT), userLevel.toLowerCase(Locale.ROOT)));
            }
        }

        if (p.minSalaryInr() != null && job.salaryMax() != null && "INR".equalsIgnoreCase(job.salaryCurrency())) {
            double fit = job.salaryMax() >= p.minSalaryInr() ? 1.0 : job.salaryMax() >= 0.8 * p.minSalaryInr() ? 0.5 : 0.0;
            parts.add(fit);
            if (fit < 1.0) {
                reasons.add("Listed pay tops out below your minimum");
            }
        }
        return parts.isEmpty() ? null : parts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Share of the role's meaningful words that appear in the job title ("Backend Engineer" vs "Senior Backend Engineer" = 1). */
    static double roleOverlap(String role, String title) {
        Set<String> roleWords = words(role);
        if (roleWords.isEmpty()) {
            roleWords = Arrays.stream(role.toLowerCase(Locale.ROOT).split("\\W+")).filter(w -> !w.isBlank())
                    .collect(Collectors.toSet());
        }
        Set<String> titleWords = Arrays.stream(title.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+"))
                .filter(w -> !w.isBlank()).collect(Collectors.toSet());
        if (roleWords.isEmpty()) {
            return 0;
        }
        long hit = roleWords.stream().filter(titleWords::contains).count();
        return (double) hit / roleWords.size();
    }

    private static Set<String> words(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+"))
                .filter(w -> !w.isBlank() && !TITLE_STOPWORDS.contains(w))
                .collect(Collectors.toSet());
    }

    static String levelFromYears(BigDecimal years) {
        if (years == null) {
            return null;
        }
        double y = years.doubleValue();
        if (y < 2) {
            return "ENTRY";
        }
        if (y < 5) {
            return "MID";
        }
        if (y < 9) {
            return "SENIOR";
        }
        return y < 14 ? "LEAD" : "PRINCIPAL";
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 1000) / 1000.0;
    }
}
