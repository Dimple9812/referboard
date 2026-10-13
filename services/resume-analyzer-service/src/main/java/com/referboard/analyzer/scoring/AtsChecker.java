package com.referboard.analyzer.scoring;

import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.scoring.MatchReport.AtsCheck;
import com.referboard.analyzer.scoring.MatchReport.CheckStatus;
import com.referboard.skills.SkillTaxonomy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks for what applicant tracking systems trip on: unreadable files, missing contact details or standard
 * headings, length, title wording, literal keyword coverage, undated roles, unquantified bullets, and skills
 * written differently from the posting ("K8s" vs "Kubernetes": ATS matching is often literal).
 */
public final class AtsChecker {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d[\\d\\s().-]{8,}\\d)");
    private static final Pattern EXPERIENCE_H = Pattern.compile("(?im)^\\W*(work |professional )?(experience|employment"
            + "( history)?|work history|career history)\\W*$");
    private static final Pattern EDUCATION_H = Pattern.compile("(?im)^\\W*(education|academics?|academic (background|"
            + "qualifications?)|qualifications)\\W*$");
    private static final Pattern SKILLS_H = Pattern.compile("(?im)^\\W*((technical |key |core )?skills|technologies|"
            + "tech stack|technical expertise|competencies)\\W*.*$");
    private static final Set<String> TITLE_NOISE = Set.of("senior", "sr", "junior", "jr", "lead", "staff", "principal",
            "i", "ii", "iii", "-", "–", "/", "&", "and", "of", "the", "for", "at");

    private AtsChecker() {
    }

    public static List<AtsCheck> check(ResumeContent content, String text, Integer pageCount, ParsedJd jd,
                                       double keywordCoverage, List<String> missingKeywords,
                                       List<String[]> aliasMismatches) {
        List<AtsCheck> out = new ArrayList<>();
        String t = text == null ? "" : text;

        out.add(t.strip().length() >= 400
                ? ok("TEXT_READABLE", "Text can be read", "The file's text extracts cleanly.")
                : fail("TEXT_READABLE", "Text can be read", "Very little text could be read from this file. If it's "
                + "a scan or an image-heavy export, many ATS will see an almost empty resume. Export a text PDF or DOCX."));

        boolean email = EMAIL.matcher(t).find() || notBlank(content.email());
        boolean phone = PHONE.matcher(t).find() || notBlank(content.phone());
        out.add(email && phone ? ok("CONTACT_INFO", "Contact details", "Email and phone found.")
                : (email || phone ? warn("CONTACT_INFO", "Contact details", "Add your " + (email ? "phone number"
                : "email address") + " in plain text at the top.")
                : fail("CONTACT_INFO", "Contact details", "No email or phone found in the text. Put them in the body, "
                + "not a header, footer or image.")));

        List<String> missingHeadings = new ArrayList<>();
        if (!EXPERIENCE_H.matcher(t).find()) {
            missingHeadings.add("Experience");
        }
        if (!EDUCATION_H.matcher(t).find()) {
            missingHeadings.add("Education");
        }
        if (!SKILLS_H.matcher(t).find()) {
            missingHeadings.add("Skills");
        }
        out.add(missingHeadings.isEmpty() ? ok("SECTION_HEADINGS", "Standard section headings",
                "Experience, Education and Skills headings found.")
                : (missingHeadings.size() == 1 ? warn("SECTION_HEADINGS", "Standard section headings",
                "Use a plain \"" + missingHeadings.getFirst() + "\" heading so ATS can find that section.")
                : fail("SECTION_HEADINGS", "Standard section headings", "Use plain headings ("
                + String.join(", ", missingHeadings) + ") so ATS can split your resume into sections.")));

        int words = t.isBlank() ? 0 : t.trim().split("\\s+").length;
        if (pageCount != null) {
            out.add(pageCount <= 2 ? ok("LENGTH", "Length", pageCount + (pageCount == 1 ? " page." : " pages."))
                    : pageCount == 3 ? warn("LENGTH", "Length", "3 pages. Two is the norm; trim older roles.")
                    : fail("LENGTH", "Length", pageCount + " pages. Cut to two pages: recruiters skim."));
        } else {
            out.add(words < 250 ? warn("LENGTH", "Length", "About " + words + " words: add detail to your recent roles.")
                    : words > 1400 ? warn("LENGTH", "Length", "About " + words + " words: likely over two pages.")
                    : ok("LENGTH", "Length", "About " + words + " words."));
        }

        List<String> titleWords = titleWords(jd.title());
        if (!titleWords.isEmpty()) {
            String lower = t.toLowerCase(Locale.ROOT);
            boolean all = titleWords.stream().allMatch(lower::contains);
            out.add(all ? ok("TITLE_MATCH", "Job title wording", "Your resume uses the posting's title wording.")
                    : warn("TITLE_MATCH", "Job title wording", "The posting says \"" + String.join(" ", titleWords)
                    + "\". If that truthfully describes your work, use the same words in your headline or summary."));
        }

        int pct = (int) Math.round(keywordCoverage * 100);
        String missingList = missingKeywords.isEmpty() ? "" : " Missing: " + String.join(", ",
                missingKeywords.subList(0, Math.min(8, missingKeywords.size()))) + ".";
        out.add(pct >= 70 ? ok("KEYWORD_COVERAGE", "Keyword coverage", pct + "% of the posting's key terms appear."
                + missingList)
                : pct >= 45 ? warn("KEYWORD_COVERAGE", "Keyword coverage", pct + "% of the posting's key terms appear."
                + missingList)
                : fail("KEYWORD_COVERAGE", "Keyword coverage", "Only " + pct + "% of the posting's key terms appear."
                + missingList));

        long undated = content.experiences().stream().filter(e -> !notBlank(e.startDate())).count();
        if (!content.experiences().isEmpty()) {
            out.add(undated == 0 ? ok("DATES", "Dates on every role", "Every role has dates.")
                    : warn("DATES", "Dates on every role", undated + " role(s) have no dates. ATS use dates to "
                    + "compute your experience."));
        }

        List<String> bullets = content.experiences().stream().flatMap(e -> e.bullets().stream()).toList();
        if (!bullets.isEmpty()) {
            long quantified = bullets.stream().filter(b -> b.matches(".*\\d.*")).count();
            int share = (int) Math.round(100.0 * quantified / bullets.size());
            out.add(share >= 30 ? ok("QUANTIFIED", "Measurable results", share + "% of bullets include numbers.")
                    : warn("QUANTIFIED", "Measurable results", "Only " + share + "% of bullets include numbers. Add "
                    + "real figures you have (scale, %, time saved, team size); never estimate ones you don't."));
        }

        if (!aliasMismatches.isEmpty()) {
            List<String> pairs = aliasMismatches.stream().limit(5)
                    .map(p -> "\"" + p[0] + "\" (you wrote \"" + p[1] + "\")").toList();
            out.add(warn("SKILL_WORDING", "Skill names as the posting writes them", "Some ATS match words literally. "
                    + "Also write " + String.join(", ", pairs) + "."));
        } else {
            out.add(ok("SKILL_WORDING", "Skill names as the posting writes them",
                    "Matching skills use the posting's wording."));
        }
        return out;
    }

    static List<String> titleWords(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String core = title.replaceAll("\\(.*?\\)", " ").split("[,|:]| - | – ")[0];
        return Arrays.stream(core.toLowerCase(Locale.ROOT).trim().split("\\s+"))
                .filter(w -> !w.isBlank() && !TITLE_NOISE.contains(w.replaceAll("\\.$", "")))
                .limit(3).toList();
    }

    public static double statusCredit(CheckStatus s) {
        return switch (s) {
            case PASS -> 1.0;
            case WARN -> 0.5;
            case FAIL -> 0.0;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static AtsCheck ok(String id, String label, String detail) {
        return new AtsCheck(id, label, CheckStatus.PASS, detail);
    }

    private static AtsCheck warn(String id, String label, String detail) {
        return new AtsCheck(id, label, CheckStatus.WARN, detail);
    }

    private static AtsCheck fail(String id, String label, String detail) {
        return new AtsCheck(id, label, CheckStatus.FAIL, detail);
    }
}
