package com.referboard.analyzer.scoring;

import com.referboard.analyzer.model.ResumeContent;
import com.referboard.skills.SkillTaxonomy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How well a resume backs each skill:
 * <ul>
 *   <li>STRONG: used in a role (named in that role's title or bullets);</li>
 *   <li>WEAK: only listed (skills section, summary, certifications or elsewhere in the file);</li>
 *   <li>NONE: not on the resume.</li>
 * </ul>
 * Recruiters and ATS rankers both weigh "used at work" above "listed", so the score does too.
 */
public final class SkillEvidence {

    public enum Level { STRONG, WEAK, NONE }

    public record Found(Level level, String where) {
    }

    private final SkillTaxonomy taxonomy;
    private final Map<String, String> usedIn = new HashMap<>();   // skill → "Title at Company" (first, most recent)
    private final Set<String> listed = new HashSet<>();
    private final String lowerText;

    public SkillEvidence(SkillTaxonomy taxonomy, ResumeContent content, String resumeText) {
        this.taxonomy = taxonomy;
        for (ResumeContent.Experience e : content.experiences()) {
            String roleText = (e.title() == null ? "" : e.title()) + "\n" + String.join("\n", e.bullets());
            String label = role(e);
            for (String s : taxonomy.extract(roleText)) {
                usedIn.putIfAbsent(s, label);
            }
        }
        listed.addAll(taxonomy.normalizeAll(content.skills()));
        listed.addAll(taxonomy.extract(String.join("\n", nz(content.summary()), nz(content.headline()),
                String.join("\n", content.certifications()))));
        listed.addAll(taxonomy.extract(resumeText));
        this.lowerText = (resumeText + "\n" + content.toPlainText()).toLowerCase(Locale.ROOT);
    }

    public Found find(String skill) {
        String canonical = taxonomy.normalize(skill);
        if (usedIn.containsKey(canonical)) {
            return new Found(Level.STRONG, usedIn.get(canonical));
        }
        if (listed.contains(canonical)) {
            return new Found(Level.WEAK, null);
        }
        // Skills outside the taxonomy: literal, whole-word match.
        if (!taxonomy.isKnown(skill) && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(canonical)
                + "(?![\\p{L}\\p{N}])").matcher(lowerText).find()) {
            return new Found(Level.WEAK, null);
        }
        return new Found(Level.NONE, null);
    }

    /** Fraction credited for a level when scoring skill components. */
    public static double credit(Level level) {
        return switch (level) {
            case STRONG -> 1.0;
            case WEAK -> 0.6;
            case NONE -> 0.0;
        };
    }

    static String role(ResumeContent.Experience e) {
        if (e.title() != null && e.company() != null) {
            return e.title() + " at " + e.company();
        }
        return e.company() != null ? e.company() : e.title();
    }

    /** Skills the resume lists or uses, canonical. */
    public Set<String> allSkills() {
        Set<String> all = new HashSet<>(listed);
        all.addAll(usedIn.keySet());
        return all;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static List<String> lines(String text) {
        return List.of(text.split("\n"));
    }
}
