package com.referboard.analyzer.prep;

import com.referboard.analyzer.letter.LetterFacts;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.prep.PrepItem.Example;
import com.referboard.analyzer.prep.PrepItem.Section;
import com.referboard.analyzer.prep.PrepItem.Source;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.skills.SkillTaxonomy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds an interview prep plan from one analysis: the lines of the resume interviewers will dig into, technical
 * questions for the skills the posting wants and the resume shows, the posting's own responsibilities, behavioural
 * questions for the level, honest handling of gaps, and questions to ask. Deterministic; AI questions are added
 * separately and checked.
 */
public final class PrepPlanner {

    static final int MAX_EXPERIENCE = 4;
    static final int MAX_TECHNICAL = 10;
    static final int MAX_ROLE = 3;
    static final int MAX_BEHAVIOURAL = 5;
    static final int MAX_GAPS = 4;

    /** A figure with its magnitude and unit: "40%", "2M transactions", "3 engineers", "200 ms". Not "p99". */
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z\\d])\\d[\\d,.]*(?:\\s*(?:k|m|mn|bn|cr|lakh|crore)\\b)?"
            + "(?:\\s*(?:%|x\\b|ms\\b|seconds?\\b|minutes?\\b|hours?\\b|days?\\b|users?\\b|engineers?\\b|people\\b|"
            + "transactions?\\b|requests?\\b|customers?\\b|merchants?\\b|orders?\\b))?", Pattern.CASE_INSENSITIVE);

    private final QuestionBank bank;
    private final SkillTaxonomy taxonomy;

    public PrepPlanner(QuestionBank bank, SkillTaxonomy taxonomy) {
        this.bank = bank;
        this.taxonomy = taxonomy;
    }

    public List<PrepItem> plan(ResumeContent resume, ParsedJd jd, List<MatchReport.SkillMatch> matched,
                               MatchReport.MissingSkills missing, BigDecimal years, String jobTitle, String company) {
        List<PrepItem> out = new ArrayList<>();
        LetterFacts facts = LetterFacts.from(resume, jd, matched, null, jobTitle, company, null, taxonomy);
        experience(resume, facts, out);
        technical(jd, matched, out);
        role(jd, facts, out);
        behavioural(level(jd, years), out);
        gaps(resume, missing, out);
        ask(jd, matched, company, out);
        return out;
    }

    // ----- the user's own resume lines -----

    private void experience(ResumeContent resume, LetterFacts facts, List<PrepItem> out) {
        // The bullets most relevant to this job first, then any other bullet with a number in it: a stated metric
        // is the first thing an interviewer asks you to explain.
        List<Example> chosen = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (LetterFacts.Evidence e : facts.evidence()) {
            if (seen.add(e.bullet())) {
                chosen.add(new Example(e.company(), e.title(), e.bullet()));
            }
        }
        for (ResumeContent.Experience x : resume.experiences()) {
            for (String b : x.bullets()) {
                if (b != null && NUMBER.matcher(b).find() && b.length() <= 400 && seen.add(b.strip())) {
                    chosen.add(new Example(x.company(), x.title(), b.strip()));
                }
            }
        }
        int n = 0;
        for (Example ex : chosen.subList(0, Math.min(MAX_EXPERIENCE, chosen.size()))) {
            List<String> numbers = numbers(ex.text());
            String question = "Your resume says: “" + trimEnd(ex.text()) + "”. "
                    + (numbers.isEmpty() ? "What was your part, and what was hard about it?"
                    : "How did you do it, and how did you measure the result?");
            List<String> pointers = new ArrayList<>();
            pointers.add("The situation" + (ex.company() == null ? "" : " at " + ex.company())
                    + ": the system, the problem and why it mattered");
            pointers.add("What you did yourself: say “I”, not “we”, and name the decisions you made");
            pointers.add(numbers.isEmpty() ? "The result: what changed, and how you know"
                    : "The result: " + String.join(", ", numbers) + ". Be ready to say how it was measured, from what to what");
            pointers.add("What you'd do differently now");
            out.add(new PrepItem("exp-" + (++n), Section.EXPERIENCE, question,
                    "Interviewers pick lines from your resume and dig in. Anything you wrote is fair game.",
                    pointers, ex, null, Source.RESUME));
        }
    }

    // ----- skills the posting wants that the resume shows -----

    private void technical(ParsedJd jd, List<MatchReport.SkillMatch> matched, List<PrepItem> out) {
        Set<String> onResume = new LinkedHashSet<>();
        matched.forEach(m -> onResume.add(m.name()));
        List<String> order = new ArrayList<>();
        jd.requiredSkills().stream().filter(onResume::contains).forEach(order::add);
        jd.preferredSkills().stream().filter(onResume::contains).filter(s -> !order.contains(s)).forEach(order::add);
        int n = 0;
        for (int i = 0; i < order.size() && n < MAX_TECHNICAL; i++) {
            String skill = order.get(i);
            String display = taxonomy.display(skill);
            String category = taxonomy.lookup(skill).map(SkillTaxonomy.Skill::category).orElse(null);
            List<QuestionBank.Question> qs = bank.forSkill(skill, category, display);
            // Two questions for the first two required skills when the bank has them; one for the rest.
            int take = i < 2 && bank.hasOwnQuestions(skill) ? 2 : 1;
            String usedIn = matched.stream().filter(m -> m.name().equals(skill)).map(MatchReport.SkillMatch::usedIn)
                    .filter(u -> u != null && !u.isBlank()).findFirst().orElse(null);
            for (QuestionBank.Question q : qs.subList(0, Math.min(take, qs.size()))) {
                if (n >= MAX_TECHNICAL) {
                    break;
                }
                List<String> pointers = new ArrayList<>(q.covers());
                if (usedIn != null) {
                    pointers.add("Use your own example: you used " + display + " as " + usedIn);
                }
                out.add(new PrepItem("tech-" + (++n), Section.TECHNICAL, q.q(), q.why(), pointers, null, display,
                        Source.BANK));
            }
        }
    }

    // ----- the posting's responsibilities -----

    private void role(ParsedJd jd, LetterFacts facts, List<PrepItem> out) {
        int n = 0;
        for (String resp : jd.responsibilities()) {
            if (n >= MAX_ROLE) {
                break;
            }
            String r = resp == null ? "" : resp.strip();
            if (r.length() < 12 || r.length() > 300) {
                continue;
            }
            Set<String> skills = taxonomy.extract(r);
            Example closest = facts.evidence().stream()
                    .filter(e -> e.skills().stream().map(s -> taxonomy.normalize(s)).anyMatch(skills::contains))
                    .findFirst().map(e -> new Example(e.company(), e.title(), e.bullet())).orElse(null);
            List<String> pointers = new ArrayList<>();
            pointers.add(closest != null ? "Your closest example is the line about this at " + closest.company()
                    : "Pick a real example from your work; if you haven't done this, say so and describe the closest thing");
            pointers.add("What you did, the trade-offs, and the result");
            pointers.add("How you'd approach it in this team");
            out.add(new PrepItem("role-" + (++n), Section.ROLE,
                    "The posting says you'll “" + lowerFirst(trimEnd(r)) + "”. Tell me about a time you did something similar.",
                    "They test whether you've done the actual work of this role before.", pointers, closest, null,
                    Source.POSTING));
        }
    }

    // ----- behavioural, by level -----

    private void behavioural(String level, List<PrepItem> out) {
        int n = 0;
        for (QuestionBank.Question q : bank.behavioural(level)) {
            if (n >= MAX_BEHAVIOURAL) {
                break;
            }
            out.add(new PrepItem("beh-" + (++n), Section.BEHAVIOURAL, q.q(), q.why(), q.covers(), null, null, Source.BANK));
        }
    }

    // ----- gaps: skills the posting wants that the resume doesn't show -----

    private void gaps(ResumeContent resume, MatchReport.MissingSkills missing, List<PrepItem> out) {
        List<MatchReport.SkillRef> gaps = new ArrayList<>(missing.required());
        missing.preferred().stream().filter(p -> gaps.stream().noneMatch(g -> g.name().equals(p.name()))).forEach(gaps::add);
        Set<String> have = new LinkedHashSet<>(taxonomy.normalizeAll(resume.skills()));
        have.addAll(taxonomy.extract(resume.toPlainText()));
        int n = 0;
        for (MatchReport.SkillRef gap : gaps) {
            if (n >= MAX_GAPS) {
                break;
            }
            String category = taxonomy.lookup(gap.name()).map(SkillTaxonomy.Skill::category).orElse(null);
            List<String> adjacent = have.stream()
                    .filter(h -> category != null && category.equals(taxonomy.lookup(h).map(SkillTaxonomy.Skill::category).orElse(null)))
                    .map(taxonomy::display).limit(3).toList();
            List<String> pointers = new ArrayList<>();
            pointers.add("Be honest about where you have and haven't used it; don't stretch the truth");
            pointers.add(adjacent.isEmpty() ? "Connect it to the closest thing you have done"
                    : "Connect it to what you have used: " + String.join(", ", adjacent));
            pointers.add("Say how you'd get up to speed: the docs you'd read, a small project you could build first");
            bank.first(gap.name(), category, gap.label())
                    .ifPresent(q -> pointers.add("If they go deeper: “" + q.q() + "”"));
            boolean required = missing.required().stream().anyMatch(r -> r.name().equals(gap.name()));
            out.add(new PrepItem("gap-" + (++n), Section.GAPS, "Have you worked with " + gap.label() + "?",
                    (required ? "It's a requirement" : "It's listed as a plus") + " in the posting and isn't on your resume.",
                    pointers, null, gap.label(), Source.POSTING));
        }
    }

    // ----- questions to ask them -----

    private void ask(ParsedJd jd, List<MatchReport.SkillMatch> matched, String company, List<PrepItem> out) {
        int n = 0;
        String top = jd.requiredSkills().stream().findFirst().map(taxonomy::display).orElse(null);
        if (top != null) {
            out.add(new PrepItem("ask-" + (++n), Section.ASK,
                    "How does the team use " + top + " today, and what would I work on first?",
                    "Specific to this role: shows you read the posting and want to know the real work.", List.of(), null,
                    top, Source.POSTING));
        }
        for (QuestionBank.Question q : bank.toAsk()) {
            out.add(new PrepItem("ask-" + (++n), Section.ASK, q.q(), q.why(), q.covers(), null, null, Source.BANK));
        }
    }

    // ----- helpers -----

    /** Level used to choose behavioural questions: the posting's seniority, else years on the resume. */
    static String level(ParsedJd jd, BigDecimal years) {
        if (jd.seniority() != null) {
            return switch (jd.seniority()) {
                case INTERN -> "INTERN";
                case JUNIOR -> "ENTRY";
                case MID -> "MID";
                case SENIOR -> "SENIOR";
                case LEAD, STAFF, MANAGER -> "LEAD";
                case PRINCIPAL, DIRECTOR -> "PRINCIPAL";
            };
        }
        double y = years == null ? 0 : years.doubleValue();
        return y < 1 ? "INTERN" : y < 2 ? "ENTRY" : y < 5 ? "MID" : y < 8 ? "SENIOR" : "LEAD";
    }

    /** "40%", "2M transactions": the figures a line claims, as written. */
    static List<String> numbers(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String s = m.group().strip().replaceAll("[,.]$", "");
            // Skip bare years ("2021") and single digits without a unit ("3"), which are rarely claims.
            if (s.matches("(19|20)\\d\\d") || s.matches("\\d")) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private static String trimEnd(String s) {
        return s.strip().replaceAll("[.;\\s]+$", "");
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty() || (s.length() > 1 && Character.isUpperCase(s.charAt(1)))) {
            return s;   // keep acronyms like "API…"
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
