package com.referboard.analyzer.suggest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.referboard.ai.JsonSchemas;
import com.referboard.ai.LlmClient;
import com.referboard.ai.StructuredPrompt;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.analyzer.scoring.MatchReport.SkillRef;
import com.referboard.analyzer.suggest.Suggestion.Origin;
import com.referboard.analyzer.suggest.Suggestion.Priority;
import com.referboard.analyzer.suggest.Suggestion.Target;
import com.referboard.analyzer.suggest.Suggestion.Type;
import com.referboard.skills.SkillTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the improvement list. Rules always run (skills order, posting's skill wording, missing skills to add if
 * true, summary from facts, advice); the LLM, when configured, adds bullet rewrites and a better summary, each
 * checked by {@link TruthGuard}. Impact and priority are filled in by the caller from real re-scoring.
 */
@Component
public class SuggestionEngine {

    private static final Logger log = LoggerFactory.getLogger(SuggestionEngine.class);
    public static final String PROMPT_VERSION = "suggest-v1";
    private static final String SYSTEM = JsonSchemas.loadText("prompts/suggest-system.txt");
    private static final JsonNode SCHEMA = JsonSchemas.load("prompts/suggest-schema.json");
    static final int MAX_ADD_SKILL = 8;

    public record Output(List<Suggestion> suggestions, boolean aiUsed, List<String> notices) {
    }

    record AiOutput(List<BulletRewrite> bulletRewrites, String summary, String gapAdvice, String seniorityAdvice) {
    }

    record BulletRewrite(Integer experienceIndex, Integer bulletIndex, String rewritten, String reason) {
    }

    private final LlmClient llm;
    private final SkillTaxonomy taxonomy;
    private final ObjectMapper mapper;

    public SuggestionEngine(LlmClient llm, SkillTaxonomy taxonomy, ObjectMapper mapper) {
        this.llm = llm;
        this.taxonomy = taxonomy;
        this.mapper = mapper;
    }

    public Output suggest(ResumeContent resume, ParsedJd jd, MatchReport report, BigDecimal years) {
        List<Suggestion> out = new ArrayList<>(rules(resume, jd, report, years));
        List<String> notices = new ArrayList<>();
        boolean aiUsed = false;
        if (llm.available()) {
            try {
                String yearsText = yearsText(years);
                AiOutput ai = llm.structured(new StructuredPrompt<>(SYSTEM, aiInput(resume, jd, report, yearsText),
                        "record_resume_suggestions", "Record the tailoring suggestions.", SCHEMA, AiOutput.class,
                        3000, 0.2));
                List<Suggestion> aiSuggestions = fromAi(ai, resume, yearsText);
                if (!aiSuggestions.isEmpty()) {
                    // An AI summary replaces the rule-based one; AI advice replaces rule advice of the same type.
                    Set<Type> replaced = new java.util.HashSet<>();
                    aiSuggestions.forEach(s -> replaced.add(s.type()));
                    out.removeIf(s -> s.origin() == Origin.RULES && replaced.contains(s.type())
                            && (s.type() == Type.SUMMARY_REWRITE || s.type() == Type.GAP_ADVICE
                            || s.type() == Type.SENIORITY_ADVICE));
                    out.addAll(aiSuggestions);
                    aiUsed = true;
                }
            } catch (RuntimeException e) {
                log.warn("AI suggestions failed; using rule-based suggestions only: {}", e.toString());
                notices.add("AI rewrites are unavailable right now; showing rule-based suggestions.");
            }
        } else {
            notices.add("AI rewrites aren't configured, so bullet rewrites are not suggested. Skills, summary, "
                    + "ATS and gap suggestions are rule-based.");
        }
        return new Output(out, aiUsed, notices);
    }

    // ---------------------------------------------------------------------------------------------------------

    List<Suggestion> rules(ResumeContent resume, ParsedJd jd, MatchReport report, BigDecimal years) {
        List<Suggestion> out = new ArrayList<>();
        int n = 0;

        // Posting's wording for skills the resume writes differently (same skill: no confirmation needed).
        for (String skill : resume.skills()) {
            String canonical = taxonomy.normalize(skill);
            boolean inJd = jd.requiredSkills().contains(canonical) || jd.preferredSkills().contains(canonical);
            String display = taxonomy.display(canonical);
            if (inJd && display != null && !display.equalsIgnoreCase(skill.trim())
                    && !skill.toLowerCase(Locale.ROOT).contains(display.toLowerCase(Locale.ROOT))) {
                out.add(new Suggestion("rule-" + (++n), Type.SKILL_WORDING, Priority.MEDIUM,
                        "Write \"" + display + "\" as the posting does",
                        "Some ATS match words literally; \"" + skill.trim() + "\" may not count as \"" + display + "\".",
                        skill, display + " (" + skill.trim() + ")", new Target(null, null, skill, null), List.of(),
                        false, null, true, null, Origin.RULES));
            }
        }

        // Skills order: the posting's skills first.
        List<String> reordered = reorderSkills(resume.skills(), jd);
        if (!reordered.equals(resume.skills())) {
            out.add(new Suggestion("rule-" + (++n), Type.SKILLS_REORDER, Priority.MEDIUM,
                    "Lead your skills with the ones this job asks for",
                    "Recruiters skim the first few skills; put the posting's requirements there.",
                    String.join(", ", resume.skills()), String.join(", ", reordered),
                    new Target(null, null, null, reordered), List.of(), false, null, true, null, Origin.RULES));
        }

        // Missing skills: add only if true.
        int added = 0;
        for (SkillRef s : concat(report.missingSkills().required(), report.missingSkills().preferred())) {
            if (added++ == MAX_ADD_SKILL) {
                break;
            }
            boolean required = report.missingSkills().required().contains(s);
            out.add(new Suggestion("rule-" + (++n), Type.ADD_SKILL, required ? Priority.HIGH : Priority.MEDIUM,
                    "Add " + s.label() + " to your skills",
                    (required ? "Required" : "Preferred") + " by the posting and not on your resume.",
                    null, s.label(), new Target(null, null, s.label(), null), List.of(s.name()), true,
                    Suggestion.CONFIRM_LABEL, true, null, Origin.RULES));
        }

        // Listed but never shown in a role.
        List<SkillRef> weak = report.missingSkills().weaklyEvidenced();
        if (!weak.isEmpty()) {
            List<String> labels = weak.stream().map(SkillRef::label).limit(5).toList();
            out.add(new Suggestion("rule-" + (++n), Type.EVIDENCE_ADVICE, Priority.MEDIUM,
                    "Show where you used " + join(labels),
                    join(labels) + (labels.size() == 1 ? " is" : " are") + " in your skills list but no role mentions "
                            + (labels.size() == 1 ? "it" : "them") + ". If you used " + (labels.size() == 1 ? "it"
                            : "them") + " at work, say where in a bullet: experience in a role counts for more than "
                            + "a listed skill.",
                    null, null, Target.none(), List.of(), false, null, false, null, Origin.RULES));
        }

        // Summary from facts only. Replacing the user's own summary is worth it only when the new one names skills
        // this role wants; otherwise it would just drop their wording.
        String summary = ruleSummary(resume, report, years);
        boolean hasSummary = resume.summary() != null && !resume.summary().isBlank();
        if (summary != null && (!hasSummary
                || (!mentionsTitle(resume.summary(), jd.title()) && !report.matchedSkills().isEmpty()))) {
            out.add(new Suggestion("rule-" + (++n), Type.SUMMARY_REWRITE, Priority.MEDIUM,
                    resume.summary() == null || resume.summary().isBlank() ? "Add a short summary"
                            : "Point your summary at this role",
                    "A two-line summary with the skills this role wants is the first thing recruiters read.",
                    resume.summary(), summary, Target.none(), List.of(), false, null, true, null, Origin.RULES));
        }

        // Gap advice.
        List<SkillRef> missingRequired = report.missingSkills().required();
        if (!missingRequired.isEmpty()) {
            List<String> labels = missingRequired.stream().map(SkillRef::label).limit(5).toList();
            out.add(new Suggestion("rule-" + (++n), Type.GAP_ADVICE, Priority.HIGH,
                    "Address missing requirements: " + join(labels),
                    "If you have used " + (labels.size() == 1 ? "it" : "them") + ", add " + (labels.size() == 1 ? "it"
                            : "them") + " to a role's bullets, not just your skills list. If not, mention adjacent "
                            + "experience honestly, or build a small project you can talk about.",
                    null, null, Target.none(), List.of(), false, null, false, null, Origin.RULES));
        }

        // Seniority advice.
        if (jd.minYears() != null && years != null && years.doubleValue() + 0.5 < jd.minYears()) {
            double gap = jd.minYears() - years.doubleValue();
            out.add(new Suggestion("rule-" + (++n), Type.SENIORITY_ADVICE, gap > 2 ? Priority.HIGH : Priority.LOW,
                    "Experience below what's asked",
                    "The role asks for " + jd.minYears() + "+ years and your resume shows "
                            + years.stripTrailingZeros().toPlainString() + ". " + (gap <= 2
                            ? "That's close: lead with scope and ownership (systems you owned, decisions you made)."
                            : "Consider roles one level down, or make the strongest case for scope you've handled."),
                    null, null, Target.none(), List.of(), false, null, false, null, Origin.RULES));
        }
        return out;
    }

    List<String> reorderSkills(List<String> skills, ParsedJd jd) {
        List<String> required = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String s : skills) {
            String c = taxonomy.normalize(s);
            if (jd.requiredSkills().contains(c)) {
                required.add(s);
            } else if (jd.preferredSkills().contains(c)) {
                preferred.add(s);
            } else {
                rest.add(s);
            }
        }
        required.sort((a, b) -> Integer.compare(jd.requiredSkills().indexOf(taxonomy.normalize(a)),
                jd.requiredSkills().indexOf(taxonomy.normalize(b))));
        List<String> out = new ArrayList<>(required);
        out.addAll(preferred);
        out.addAll(rest);
        return out;
    }

    /** "Backend Engineer with 6+ years of experience in Java, Spring Boot and Apache Kafka, currently at Razorpay." */
    String ruleSummary(ResumeContent resume, MatchReport report, BigDecimal years) {
        String title = resume.experiences().isEmpty() ? resume.headline() : resume.experiences().getFirst().title();
        if (title == null || title.isBlank()) {
            return null;
        }
        List<String> skills = report.matchedSkills().stream()
                .sorted((a, b) -> Boolean.compare(b.required(), a.required()))
                .map(MatchReport.SkillMatch::label).distinct().limit(4).toList();
        StringBuilder sb = new StringBuilder(title.trim());
        if (years != null && years.doubleValue() >= 1) {
            int whole = years.intValue();
            sb.append(" with ").append(whole).append(years.doubleValue() > whole ? "+" : "").append(" years of experience");
        }
        if (!skills.isEmpty()) {
            sb.append(years != null && years.doubleValue() >= 1 ? " in " : " experienced in ").append(join(skills));
        }
        ResumeContent.Experience current = resume.experiences().isEmpty() ? null : resume.experiences().getFirst();
        if (current != null && current.current() && current.company() != null) {
            sb.append(", currently at ").append(current.company());
        }
        return sb.append('.').toString();
    }

    // ---------------------------------------------------------------------------------------------------------

    List<Suggestion> fromAi(AiOutput ai, ResumeContent resume, String yearsText) {
        List<Suggestion> out = new ArrayList<>();
        int n = 0;
        Set<String> seen = new LinkedHashSet<>();
        if (ai.bulletRewrites() != null) {
            for (BulletRewrite r : ai.bulletRewrites()) {
                if (r == null || r.experienceIndex() == null || r.bulletIndex() == null) {
                    continue;
                }
                int e = r.experienceIndex();
                int b = r.bulletIndex();
                if (e < 0 || e >= resume.experiences().size() || b < 0
                        || b >= resume.experiences().get(e).bullets().size() || !seen.add(e + ":" + b)) {
                    continue;
                }
                String before = resume.experiences().get(e).bullets().get(b);   // never trust the model's copy
                TruthGuard.Verdict v = TruthGuard.check(before, r.rewritten(), taxonomy,
                        Math.max(260, (int) (before.length() * 2.2)));
                if (!v.ok()) {
                    log.info("AI bullet rewrite rejected ({}): {}", v.reason(), r.rewritten());
                    continue;
                }
                ResumeContent.Experience exp = resume.experiences().get(e);
                boolean confirm = !v.skillsIntroduced().isEmpty();
                out.add(new Suggestion("ai-" + (++n), Type.BULLET_REWRITE, Priority.MEDIUM,
                        "Reword a bullet" + (exp.company() == null ? "" : " at " + exp.company()),
                        sentence(nz(r.reason())) + (confirm ? " Mentions " + join(v.skillsIntroduced().stream()
                                .map(taxonomy::display).toList()) + ", which the original bullet doesn't." : ""),
                        before, r.rewritten().strip(), new Target(e, b, null, null), v.skillsIntroduced(), confirm,
                        confirm ? Suggestion.CONFIRM_LABEL : null, true, null, Origin.AI));
            }
        }
        if (ai.summary() != null && !ai.summary().isBlank()) {
            String facts = resume.toPlainText() + (yearsText == null ? "" : "\n" + yearsText + " years");
            TruthGuard.Verdict v = TruthGuard.check(facts, ai.summary(), taxonomy, 600);
            if (v.ok()) {
                // Skills "introduced" relative to the whole resume are genuinely new claims.
                boolean confirm = !v.skillsIntroduced().isEmpty();
                out.add(new Suggestion("ai-" + (++n), Type.SUMMARY_REWRITE, Priority.MEDIUM,
                        resume.summary() == null || resume.summary().isBlank() ? "Add a short summary"
                                : "Point your summary at this role",
                        "A summary built from your resume using the posting's terms.", resume.summary(),
                        ai.summary().strip(), Target.none(), v.skillsIntroduced(), confirm,
                        confirm ? Suggestion.CONFIRM_LABEL : null, true, null, Origin.AI));
            } else {
                log.info("AI summary rejected ({})", v.reason());
            }
        }
        if (ai.gapAdvice() != null && !ai.gapAdvice().isBlank()) {
            out.add(new Suggestion("ai-" + (++n), Type.GAP_ADVICE, Priority.HIGH, "Address missing requirements",
                    ai.gapAdvice().strip(), null, null, Target.none(), List.of(), false, null, false, null, Origin.AI));
        }
        if (ai.seniorityAdvice() != null && !ai.seniorityAdvice().isBlank()) {
            out.add(new Suggestion("ai-" + (++n), Type.SENIORITY_ADVICE, Priority.LOW, "Level and experience",
                    ai.seniorityAdvice().strip(), null, null, Target.none(), List.of(), false, null, false, null,
                    Origin.AI));
        }
        return out;
    }

    String aiInput(ResumeContent resume, ParsedJd jd, MatchReport report, String yearsText) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode r = root.putObject("resume");
        r.put("yearsOfExperience", yearsText);
        r.put("headline", resume.headline());
        r.put("summary", resume.summary());
        r.set("skills", mapper.valueToTree(resume.skills()));
        ArrayNode exps = r.putArray("experiences");
        for (int i = 0; i < resume.experiences().size(); i++) {
            ResumeContent.Experience e = resume.experiences().get(i);
            ObjectNode x = exps.addObject();
            x.put("experienceIndex", i);
            x.put("title", e.title());
            x.put("company", e.company());
            x.put("current", e.current());
            ArrayNode bullets = x.putArray("bullets");
            for (int j = 0; j < e.bullets().size(); j++) {
                bullets.addObject().put("bulletIndex", j).put("text", e.bullets().get(j));
            }
        }
        ObjectNode j = root.putObject("job");
        j.put("title", jd.title());
        j.set("requiredSkills", mapper.valueToTree(jd.requiredSkills().stream().map(taxonomy::display).toList()));
        j.set("preferredSkills", mapper.valueToTree(jd.preferredSkills().stream().map(taxonomy::display).toList()));
        j.set("responsibilities", mapper.valueToTree(jd.responsibilities()));
        j.set("keywords", mapper.valueToTree(jd.keywords()));
        j.put("minYears", jd.minYears());
        j.put("seniority", jd.seniority() == null ? null : jd.seniority().name());
        ObjectNode a = root.putObject("analysis");
        a.set("missingRequired", mapper.valueToTree(report.missingSkills().required().stream().map(SkillRef::label).toList()));
        a.set("listedButNotShownInRoles", mapper.valueToTree(report.missingSkills().weaklyEvidenced().stream()
                .map(SkillRef::label).toList()));
        try {
            return "<RESUME_AND_JOB>\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                    + "\n</RESUME_AND_JOB>";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 6.3 → "6+", 4.0 → "4"; null under a year. */
    public static String yearsText(BigDecimal years) {
        if (years == null || years.doubleValue() < 1) {
            return null;
        }
        int whole = years.intValue();
        return whole + (years.doubleValue() > whole ? "+" : "");
    }

    private static boolean mentionsTitle(String summary, String title) {
        if (title == null) {
            return true;
        }
        String s = summary.toLowerCase(Locale.ROOT);
        return com.referboard.analyzer.scoring.AtsCheckerAccess.titleWords(title).stream().allMatch(s::contains);
    }

    static String sentence(String s) {
        return s.isEmpty() || s.matches(".*[.!?]$") ? s : s + ".";
    }

    static String join(List<String> items) {
        List<String> l = items.stream().filter(Objects::nonNull).toList();
        if (l.isEmpty()) {
            return "";
        }
        if (l.size() == 1) {
            return l.getFirst();
        }
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.getLast();
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}
