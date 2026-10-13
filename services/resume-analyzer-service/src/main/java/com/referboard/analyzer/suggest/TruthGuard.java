package com.referboard.analyzer.suggest;

import com.referboard.skills.SkillTaxonomy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps AI rewrites honest. A rewrite may rephrase, reorder and use the posting's terminology, but:
 * <ul>
 *   <li>it may not contain a number the source doesn't (no invented metrics, team sizes or years) — rejected;</li>
 *   <li>it may not contain placeholders like "[X]%" — rejected;</li>
 *   <li>any skill it names that the source doesn't is listed in {@code skillsIntroduced}, and the suggestion
 *       requires the user to confirm it before it can be applied.</li>
 * </ul>
 */
public final class TruthGuard {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]{0,40}]|\\{[^}]{0,40}}|\\bX+%|\\bN\\+? (?:years|%)|"
            + "\\bTBD\\b|\\bXX\\b");

    private TruthGuard() {
    }

    public record Verdict(boolean ok, String reason, List<String> skillsIntroduced) {
    }

    public static Verdict check(String source, String rewrite, SkillTaxonomy taxonomy, int maxLength) {
        if (rewrite == null || rewrite.isBlank()) {
            return new Verdict(false, "empty", List.of());
        }
        String r = rewrite.strip();
        if (r.equalsIgnoreCase(source == null ? "" : source.strip())) {
            return new Verdict(false, "unchanged", List.of());
        }
        if (r.length() > maxLength) {
            return new Verdict(false, "too long", List.of());
        }
        if (PLACEHOLDER.matcher(r).find()) {
            return new Verdict(false, "placeholder", List.of());
        }
        Optional<String> invented = newNumber(source == null ? "" : source, r);
        if (invented.isPresent()) {
            return new Verdict(false, "number not in source: " + invented.get(), List.of());
        }
        Set<String> before = taxonomy.extract(source == null ? "" : source);
        List<String> introduced = new ArrayList<>();
        for (String s : taxonomy.extract(r)) {
            if (!before.contains(s)) {
                introduced.add(s);
            }
        }
        return new Verdict(true, null, introduced);
    }

    /** First number token in {@code text} that {@code source} doesn't contain as a whole token. */
    static Optional<String> newNumber(String source, String text) {
        Set<String> known = new HashSet<>();
        Matcher s = NUMBER.matcher(source);
        while (s.find()) {
            known.add(norm(s.group()));
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            if (!known.contains(norm(m.group()))) {
                return Optional.of(m.group());
            }
        }
        return Optional.empty();
    }

    private static String norm(String n) {
        return n.replaceAll("[.,]+$", "").replace(",", "");
    }
}
