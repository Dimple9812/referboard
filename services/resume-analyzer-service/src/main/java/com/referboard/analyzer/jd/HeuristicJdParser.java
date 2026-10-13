package com.referboard.analyzer.jd;

import com.referboard.analyzer.model.Levels;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ParsedJd.DegreeLevel;
import com.referboard.analyzer.model.ParsedJd.EducationRequirement;
import com.referboard.analyzer.model.ParsedJd.Seniority;
import com.referboard.skills.SkillTaxonomy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based JD parsing, used when no LLM is configured (and as the cross-check for LLM output). Finds sections by
 * their headings ("Requirements", "Nice to have", "What you'll do", "Benefits"...), takes skills from the
 * taxonomy, years from "N+ years", and responsibilities from bullet lines.
 */
public class HeuristicJdParser {

    enum Section { NONE, REQUIRED, PREFERRED, RESPONSIBILITIES, OTHER }

    private static final Pattern REQUIRED_H = Pattern.compile("(requirement|qualification|must[- ]have|what you('ll| will)? "
            + "(need|bring)|what we('re| are) looking for|you have|you bring|skills required|required skills|"
            + "who you are|about you|your profile|eligibility|essential)");
    private static final Pattern PREFERRED_H = Pattern.compile("(nice[- ]to[- ]have|preferred|bonus|good[- ]to[- ]have|"
            + "plus points?|it would be great|desirable|extra credit)");
    private static final Pattern RESP_H = Pattern.compile("(responsibilit|what you('ll| will) do|the role|your role|"
            + "day[- ]to[- ]day|what you('ll| will) work on|key duties|the job|job description|role overview|"
            + "in this role|impact you('ll| will) make)");
    private static final Pattern OTHER_H = Pattern.compile("(about (us|the company|the team)|benefits|perks|why (join|"
            + "us|work)|compensation|salary|equal opportunit|our (culture|values|mission)|life at|how to apply|"
            + "what we offer|interview process|diversity)");
    private static final Pattern INLINE_PREFERRED = Pattern.compile("(?i)\\b(nice to have|preferred|bonus|is a plus|"
            + "would be a plus|good to have|desirable|ideally)\\b");
    private static final Pattern YEARS = Pattern.compile("(?i)(?<![\\d.,])(\\d{1,2})\\s*(?:\\+|plus)?\\s*(?:(?:-|–|to)\\s*(\\d{1,2}))?"
            + "\\s*\\+?\\s*(?:years?|yrs?)");
    private static final Pattern FIELD = Pattern.compile("(?i)\\b(computer science|computer engineering|information "
            + "technology|software engineering|electronics|electrical engineering|mathematics|statistics|data science|"
            + "engineering)\\b");
    private static final Pattern DEGREE = Pattern.compile("(?i)\\b(ph\\.?d|doctorate|master'?s?|m\\.?tech|m\\.?e\\b|"
            + "m\\.?s\\b|mca|bachelor'?s?|b\\.?tech|b\\.?e\\b|b\\.?s\\b|bca|degree|graduate|diploma)");

    /** Common multi-word role terms ATS keyword matching cares about but the skill taxonomy doesn't hold. */
    static final List<String> DOMAIN_TERMS = List.of("distributed systems", "system design", "microservices",
            "high availability", "scalability", "low latency", "high throughput", "event-driven", "rest apis",
            "api design", "ci/cd", "code review", "mentoring", "on-call", "observability", "monitoring",
            "performance tuning", "data pipelines", "data modeling", "machine learning", "a/b testing",
            "payments", "fintech", "e-commerce", "saas", "b2b", "agile", "scrum", "test automation", "unit testing",
            "security", "cloud", "infrastructure as code", "cross-functional", "stakeholder management",
            "product sense", "ownership", "technical leadership", "architecture");

    private final SkillTaxonomy taxonomy;

    public HeuristicJdParser(SkillTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    public ParsedJd parse(String text, String titleHint, String companyHint) {
        List<String> lines = List.of(text.split("\n"));
        Section current = Section.NONE;
        StringBuilder required = new StringBuilder();
        StringBuilder preferred = new StringBuilder();
        StringBuilder untyped = new StringBuilder();
        List<String> responsibilities = new ArrayList<>();
        List<String> otherBullets = new ArrayList<>();
        boolean sawRequiredHeading = false;
        String firstLine = null;

        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            if (firstLine == null) {
                firstLine = l;
            }
            Section heading = heading(l);
            if (heading != null) {
                current = heading;
                sawRequiredHeading |= heading == Section.REQUIRED;
                continue;
            }
            boolean bullet = l.startsWith("- ");
            String content = bullet ? l.substring(2).trim() : l;
            switch (current) {
                case REQUIRED -> (INLINE_PREFERRED.matcher(content).find() ? preferred : required)
                        .append(content).append('\n');
                case PREFERRED -> preferred.append(content).append('\n');
                case RESPONSIBILITIES -> {
                    untyped.append(content).append('\n');
                    if (bullet || content.length() < 220) {
                        responsibilities.add(content);
                    }
                }
                case OTHER -> { }
                case NONE -> {
                    (INLINE_PREFERRED.matcher(content).find() ? preferred : untyped).append(content).append('\n');
                    if (bullet) {
                        otherBullets.add(content);
                    }
                }
            }
        }

        Set<String> req = new LinkedHashSet<>(taxonomy.extract(required.toString()));
        if (!sawRequiredHeading || req.isEmpty()) {
            req.addAll(taxonomy.extract(untyped.toString()));
        } else {
            // Tools named in responsibilities are effectively required too ("build services in Go").
            req.addAll(taxonomy.extract(untyped.toString()));
        }
        Set<String> pref = new LinkedHashSet<>(taxonomy.extract(preferred.toString()));
        pref.removeAll(req);

        String reqText = required.length() > 0 ? required.toString() : text;
        Integer[] years = years(reqText);
        if (years[0] == null) {
            years = years(text);
        }
        String title = titleHint != null && !titleHint.isBlank() ? titleHint
                : firstLine != null && firstLine.length() <= 80 && heading(firstLine) == null ? firstLine : null;
        Seniority seniority = Levels.fromTitle(title);
        if (seniority == null && years[0] != null) {
            seniority = years[0] < 2 ? Seniority.JUNIOR : years[0] < 5 ? Seniority.MID : years[0] < 8
                    ? Seniority.SENIOR : Seniority.LEAD;
        }
        if (responsibilities.isEmpty()) {
            responsibilities.addAll(otherBullets.stream().limit(10).toList());
        }

        String lower = text.toLowerCase(Locale.ROOT);
        List<String> keywords = new ArrayList<>();
        for (String term : DOMAIN_TERMS) {
            if (lower.contains(term)) {
                keywords.add(term);
            }
        }
        return new ParsedJd(title, companyHint, seniority, years[0], years[1], List.copyOf(req), List.copyOf(pref),
                responsibilities.stream().limit(15).toList(), keywords,
                education(required.length() > 0 ? required.toString() : text), null,
                lower.contains("remote") ? Boolean.TRUE : null);
    }

    /**
     * A line is a heading when it ends with a colon (≤ 10 words), or is very short (≤ 5 words) with no digits and no
     * sentence punctuation, so "You have 5+ years of Java" stays content while "What you'll need" is a heading.
     */
    static Section heading(String line) {
        String raw = line.trim();
        if (raw.startsWith("- ") || raw.length() > 80 || raw.isEmpty()) {
            return null;
        }
        boolean colon = raw.endsWith(":");
        String l = raw.toLowerCase(Locale.ROOT).replaceAll("[#*:]+", " ").trim();
        int words = l.isEmpty() ? 0 : l.split("\\s+").length;
        boolean shortLabel = words <= 5 && !l.matches(".*\\d.*") && !l.matches(".*[.,;!?]$");
        if (!(colon && words <= 10) && !shortLabel) {
            return null;
        }
        if (OTHER_H.matcher(l).find()) {
            return Section.OTHER;
        }
        if (PREFERRED_H.matcher(l).find()) {
            return Section.PREFERRED;
        }
        if (REQUIRED_H.matcher(l).find()) {
            return Section.REQUIRED;
        }
        if (RESP_H.matcher(l).find()) {
            return Section.RESPONSIBILITIES;
        }
        return null;
    }

    /** First "N+ years" (or "N-M years") mentioned; years above 30 are ignored as noise. */
    static Integer[] years(String text) {
        Matcher m = YEARS.matcher(text);
        while (m.find()) {
            int min = Integer.parseInt(m.group(1));
            Integer max = m.group(2) == null ? null : Integer.parseInt(m.group(2));
            if (min <= 30 && (max == null || (max >= min && max <= 40))) {
                return new Integer[] {min, max};
            }
        }
        return new Integer[] {null, null};
    }

    static EducationRequirement education(String text) {
        Matcher d = DEGREE.matcher(text);
        DegreeLevel lowest = null;
        while (d.find()) {
            String token = d.group(1).toLowerCase(Locale.ROOT).replace(".", "");
            DegreeLevel level = token.startsWith("ph") || token.startsWith("doctor") ? DegreeLevel.PHD
                    : token.startsWith("master") || token.startsWith("mtech") || token.equals("me") || token.equals("ms")
                    || token.equals("mca") ? DegreeLevel.MASTERS
                    : token.startsWith("diploma") ? DegreeLevel.DIPLOMA : DegreeLevel.BACHELORS;
            if (lowest == null || level.ordinal() < lowest.ordinal()) {
                lowest = level;
            }
        }
        if (lowest == null) {
            return new EducationRequirement(DegreeLevel.NONE, List.of(), false);
        }
        Set<String> fields = new LinkedHashSet<>();
        Matcher f = FIELD.matcher(text);
        while (f.find()) {
            fields.add(f.group(1).toLowerCase(Locale.ROOT));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean mandatory = !(lower.contains("or equivalent") || lower.contains("equivalent experience")
                || lower.contains("or related experience"));
        return new EducationRequirement(lowest, List.copyOf(fields), mandatory);
    }
}
