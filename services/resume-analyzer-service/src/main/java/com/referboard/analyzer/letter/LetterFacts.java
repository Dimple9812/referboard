package com.referboard.analyzer.letter;

import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.skills.SkillTaxonomy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything a cover letter may say, taken from the resume, the posting and the user's own words. Nothing else is
 * allowed in: the template writer only arranges these facts, and AI letters are checked against {@link #source()}.
 *
 * @param evidence  the user's own bullets that best show skills this job asks for, strongest first
 * @param motivation why this company, in the user's words (optional; never invented)
 */
public record LetterFacts(
        String fullName,
        String email,
        String phone,
        String jobTitle,
        String company,
        String currentTitle,
        String currentCompany,
        String yearsText,
        List<String> matchedSkills,
        List<Evidence> evidence,
        String motivation,
        String resumeText
) {

    /** @param experienceIndex 0 = the most recent role; examples are written in this order ("Before that, …"). */
    public record Evidence(int experienceIndex, String company, String title, String bullet, List<String> skills) {
    }

    private static final Pattern HAS_NUMBER = Pattern.compile("\\d");

    public static LetterFacts from(ResumeContent resume, ParsedJd jd, List<MatchReport.SkillMatch> matchedSkills,
                                   String yearsText,
                                   String jobTitle, String company, String motivation, SkillTaxonomy taxonomy) {
        ResumeContent.Experience current = resume.experiences().isEmpty() ? null : resume.experiences().getFirst();
        // Skills this role wants that the resume shows, required first; only those used in a role count as strong.
        List<MatchReport.SkillMatch> matched = matchedSkills.stream()
                .sorted(Comparator.comparing((MatchReport.SkillMatch m) -> !m.required())
                        .thenComparing(m -> m.evidence() != com.referboard.analyzer.scoring.SkillEvidence.Level.STRONG))
                .toList();
        Set<String> wanted = new LinkedHashSet<>();
        matched.forEach(m -> wanted.add(m.name()));
        Set<String> required = new LinkedHashSet<>(jd.requiredSkills());

        List<Evidence> candidates = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (int e = 0; e < resume.experiences().size(); e++) {
            ResumeContent.Experience x = resume.experiences().get(e);
            for (String b : x.bullets()) {
                if (b == null || b.isBlank() || b.length() > 400) {
                    continue;
                }
                List<String> skills = taxonomy.extract(b).stream().filter(wanted::contains).toList();
                if (skills.isEmpty()) {
                    continue;
                }
                int score = skills.stream().mapToInt(s -> required.contains(s) ? 3 : 1).sum()
                        + (HAS_NUMBER.matcher(b).find() ? 2 : 0)   // a measured result reads better
                        + (e == 0 ? 1 : 0);                         // recent work first among equals
                candidates.add(new Evidence(e, x.company(), x.title(), b.strip(),
                        skills.stream().map(taxonomy::display).toList()));
                scores.add(score);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(scores.get(b), scores.get(a)));
        List<Evidence> evidence = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        for (int i : order) {
            Evidence ev = candidates.get(i);
            // Prefer bullets that add a skill not shown yet, so two examples don't repeat each other.
            if (evidence.size() < 3 && (evidence.isEmpty() || !covered.containsAll(lower(ev.skills())))) {
                evidence.add(ev);
                covered.addAll(lower(ev.skills()));
            }
        }

        // Chosen by relevance, told newest first, so "Before that" is true.
        evidence.sort(Comparator.comparingInt(Evidence::experienceIndex));

        String title = blank(jobTitle) ? jd.title() : jobTitle;
        String co = blank(company) ? jd.company() : company;
        return new LetterFacts(resume.fullName(), resume.email(), resume.phone(), trim(title), trim(co),
                current == null ? trim(resume.headline()) : trim(current.title()),
                current != null && current.current() ? trim(current.company()) : null,
                yearsText,
                matched.stream().map(MatchReport.SkillMatch::label).distinct().limit(6).toList(),
                List.copyOf(evidence), blank(motivation) ? null : motivation.strip(),
                resume.toPlainText());
    }

    /** What an AI letter may draw on: numbers and skills outside this text are rejected. */
    public String source() {
        return String.join("\n", resumeText == null ? "" : resumeText,
                yearsText == null ? "" : yearsText + " years",
                motivation == null ? "" : motivation,
                jobTitle == null ? "" : jobTitle,
                company == null ? "" : company);
    }

    private static List<String> lower(List<String> xs) {
        return xs.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
