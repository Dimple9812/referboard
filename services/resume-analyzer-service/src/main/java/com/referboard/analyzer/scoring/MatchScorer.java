package com.referboard.analyzer.scoring;

import com.referboard.ai.EmbeddingClient;
import com.referboard.analyzer.model.Levels;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ParsedJd.DegreeLevel;
import com.referboard.analyzer.model.ParsedJd.Seniority;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.scoring.MatchReport.Component;
import com.referboard.analyzer.scoring.MatchReport.MissingSkills;
import com.referboard.analyzer.scoring.MatchReport.SkillMatch;
import com.referboard.analyzer.scoring.MatchReport.SkillRef;
import com.referboard.skills.ExperienceMath;
import com.referboard.skills.SkillTaxonomy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The weighted match score (0–100). Weights: required skills 40, preferred skills 15, experience/seniority 15,
 * responsibilities similarity 15, keywords/ATS 10, education 5. A component the JD gives nothing to compare on
 * (e.g. no education stated) is left out and the others are scaled up, so a JD's silence never costs points.
 * Deterministic for a given resume, JD and embedding model, which is what makes "projected score" meaningful.
 */
@org.springframework.stereotype.Component
public class MatchScorer {

    public static final int W_REQUIRED = 40;
    public static final int W_PREFERRED = 15;
    public static final int W_EXPERIENCE = 15;
    public static final int W_RESPONSIBILITIES = 15;
    public static final int W_KEYWORDS = 10;
    public static final int W_EDUCATION = 5;

    private final SkillTaxonomy taxonomy;
    private final EmbeddingClient embeddings;
    private final Clock clock;

    public MatchScorer(SkillTaxonomy taxonomy, EmbeddingClient embeddings, Clock clock) {
        this.taxonomy = taxonomy;
        this.embeddings = embeddings;
        this.clock = clock;
    }

    public MatchReport score(ResumeContent content, String text, Integer pageCount, ParsedJd jd) {
        return score(content, text, pageCount, jd, embeddings);
    }

    public MatchReport score(ResumeContent content, String text, Integer pageCount, ParsedJd jd,
                             EmbeddingClient embedder) {
        SkillEvidence evidence = new SkillEvidence(taxonomy, content, text);
        List<SkillMatch> matched = new ArrayList<>();
        List<SkillRef> missingRequired = new ArrayList<>();
        List<SkillRef> missingPreferred = new ArrayList<>();
        List<SkillRef> weak = new ArrayList<>();

        Double required = skillComponent(jd.requiredSkills(), true, evidence, matched, missingRequired, weak);
        Double preferred = skillComponent(jd.preferredSkills(), false, evidence, matched, missingPreferred, weak);

        BigDecimal years = years(content);
        Double experience = experience(jd, content, years);
        Double responsibilities = responsibilities(jd, content, embedder);

        // Keywords: the JD's non-skill terms plus its skills, matched literally as an ATS would.
        String lower = (text + "\n" + content.toPlainText()).toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>(jd.keywords());
        List<String[]> aliasMismatches = new ArrayList<>();
        for (String s : concat(jd.requiredSkills(), jd.preferredSkills())) {
            String display = taxonomy.display(s);
            terms.add(display.toLowerCase(Locale.ROOT));
            if (evidence.find(s).level() != SkillEvidence.Level.NONE && !containsTerm(lower, display)) {
                surfaceOf(s, text).ifPresent(surface -> aliasMismatches.add(new String[] {display, surface}));
            }
        }
        List<String> missingKeywords = new ArrayList<>();
        int hits = 0;
        for (String term : terms) {
            if (containsTerm(lower, term)) {
                hits++;
            } else {
                missingKeywords.add(term);
            }
        }
        double coverage = terms.isEmpty() ? 1.0 : (double) hits / terms.size();
        List<MatchReport.AtsCheck> ats = AtsChecker.check(content, text, pageCount, jd, coverage, missingKeywords,
                aliasMismatches);
        double atsRatio = ats.stream().mapToDouble(c -> AtsChecker.statusCredit(c.status())).average().orElse(1);
        Double keywords = terms.isEmpty() && ats.isEmpty() ? null : 0.75 * coverage + 0.25 * atsRatio;

        Double education = education(jd, content);

        List<Component> parts = new ArrayList<>();
        parts.add(new Component("requiredSkills", "Required skills", W_REQUIRED, required, 0,
                count(jd.requiredSkills().size() - missingRequired.size(), jd.requiredSkills().size())));
        parts.add(new Component("preferredSkills", "Preferred skills", W_PREFERRED, preferred, 0,
                count(jd.preferredSkills().size() - missingPreferred.size(), jd.preferredSkills().size())));
        parts.add(new Component("experience", "Experience and seniority", W_EXPERIENCE, experience, 0,
                experienceDetail(jd, years, content)));
        parts.add(new Component("responsibilities", "Responsibilities match", W_RESPONSIBILITIES, responsibilities, 0,
                responsibilities == null ? "The posting lists no responsibilities to compare."
                        : "How closely your bullets describe the role's duties."));
        parts.add(new Component("keywords", "Keywords and ATS", W_KEYWORDS, keywords, 0,
                Math.round(coverage * 100) + "% keyword coverage; " + ats.stream()
                        .filter(c -> c.status() == MatchReport.CheckStatus.PASS).count() + "/" + ats.size()
                        + " ATS checks passed."));
        parts.add(new Component("education", "Education", W_EDUCATION, education, 0,
                education == null ? "The posting doesn't ask for a degree." : educationDetail(jd)));

        double available = parts.stream().filter(p -> p.score() != null).mapToInt(Component::weight).sum();
        double total = 0;
        List<Component> scored = new ArrayList<>();
        for (Component p : parts) {
            double points = p.score() == null || available == 0 ? 0 : p.score() * p.weight() * 100 / available;
            total += points;
            scored.add(new Component(p.key(), p.label(), p.weight(), p.score() == null ? null : round2(p.score()),
                    round2(points), p.detail()));
        }
        int score = (int) Math.round(Math.max(0, Math.min(100, total)));
        return new MatchReport(score, MatchReport.verdictFor(score), scored, matched,
                new MissingSkills(missingRequired, missingPreferred, weak), ats, round2(coverage), missingKeywords);
    }

    private Double skillComponent(List<String> skills, boolean required, SkillEvidence evidence,
                                  List<SkillMatch> matched, List<SkillRef> missing, List<SkillRef> weak) {
        if (skills.isEmpty()) {
            return null;
        }
        double credit = 0;
        for (String s : skills) {
            SkillEvidence.Found f = evidence.find(s);
            credit += SkillEvidence.credit(f.level());
            SkillRef ref = new SkillRef(s, taxonomy.display(s));
            if (f.level() == SkillEvidence.Level.NONE) {
                missing.add(ref);
            } else {
                matched.add(new SkillMatch(s, taxonomy.display(s), required, f.level(), f.where()));
                if (f.level() == SkillEvidence.Level.WEAK) {
                    weak.add(ref);
                }
            }
        }
        return credit / skills.size();
    }

    public BigDecimal years(ResumeContent content) {
        LocalDate today = LocalDate.now(clock);
        List<ExperienceMath.Span> spans = content.experiences().stream()
                .map(e -> ExperienceMath.span(ExperienceMath.parseYearMonth(e.startDate(), today),
                        e.current() ? null : ExperienceMath.parseYearMonth(e.endDate(), today), e.current(), today))
                .toList();
        return ExperienceMath.totalYears(spans);
    }

    static Double experience(ParsedJd jd, ResumeContent content, BigDecimal years) {
        Double yearsScore = null;
        if (jd.minYears() != null && jd.minYears() > 0) {
            if (years == null) {
                yearsScore = 0.5;   // undated roles: unknown, neither credited nor zeroed
            } else {
                double y = years.doubleValue();
                if (y >= jd.minYears()) {
                    yearsScore = jd.maxYears() != null && y > jd.maxYears() + 3 ? 0.85 : 1.0;
                } else {
                    yearsScore = Math.max(0, y / jd.minYears());
                }
            }
        }
        Double levelScore = null;
        if (jd.seniority() != null) {
            String currentTitle = content.experiences().isEmpty() ? content.headline()
                    : content.experiences().getFirst().title();
            Seniority candidate = Levels.ofCandidate(currentTitle, years);
            int gap = jd.seniority().rank() - candidate.rank();
            levelScore = gap <= 0 ? (gap < -1 ? 0.8 : 1.0) : gap == 1 ? 0.7 : gap == 2 ? 0.4 : 0.15;
        }
        if (yearsScore == null && levelScore == null) {
            return null;
        }
        if (yearsScore == null) {
            return levelScore;
        }
        if (levelScore == null) {
            return yearsScore;
        }
        return 0.65 * yearsScore + 0.35 * levelScore;
    }

    /** Mean of each responsibility's best similarity to any bullet, rescaled per embedding model. */
    Double responsibilities(ParsedJd jd, ResumeContent content, EmbeddingClient embeddings) {
        List<String> bullets = content.experiences().stream().flatMap(e -> e.bullets().stream())
                .filter(b -> b != null && !b.isBlank()).toList();
        if (jd.responsibilities().isEmpty() || bullets.isEmpty()) {
            return null;
        }
        List<String> all = new ArrayList<>(jd.responsibilities());
        all.addAll(bullets);
        List<float[]> vectors = embeddings.embedAll(all);
        int r = jd.responsibilities().size();
        double sum = 0;
        for (int i = 0; i < r; i++) {
            double best = 0;
            for (int j = r; j < vectors.size(); j++) {
                best = Math.max(best, EmbeddingClient.cosine(vectors.get(i), vectors.get(j)));
            }
            sum += best;
        }
        double mean = sum / r;
        boolean lexical = embeddings.model().startsWith("hashing");
        double lo = lexical ? 0.10 : 0.20;
        double hi = lexical ? 0.45 : 0.65;
        return Math.max(0, Math.min(1, (mean - lo) / (hi - lo)));
    }

    static Double education(ParsedJd jd, ResumeContent content) {
        ParsedJd.EducationRequirement req = jd.education();
        if (req == null || req.level() == DegreeLevel.NONE) {
            return null;
        }
        if (content.educations().isEmpty()) {
            return 0.3;
        }
        DegreeLevel best = content.educations().stream().map(e -> Levels.degreeLevel(e.degree()))
                .max(Enum::compareTo).orElse(DegreeLevel.NONE);
        if (best.compareTo(req.level()) >= 0) {
            if (req.fields().isEmpty()) {
                return 1.0;
            }
            String studied = content.educations().stream()
                    .map(e -> (nz(e.fieldOfStudy()) + " " + nz(e.degree())).toLowerCase(Locale.ROOT))
                    .reduce("", (a, b) -> a + " " + b);
            boolean fieldMatch = req.fields().stream().anyMatch(f -> studied.contains(f.toLowerCase(Locale.ROOT))
                    || (f.contains("engineering") && (studied.contains("b tech") || studied.contains("btech")
                    || studied.contains("b.tech") || studied.contains("engineering"))));
            return fieldMatch ? 1.0 : 0.85;
        }
        return req.mandatory() ? 0.2 : 0.6;
    }

    /** Case-insensitive whole-term match; a trailing "s" on either side is tolerated ("api" ~ "apis"). */
    static boolean containsTerm(String lowerText, String term) {
        String t = term.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) {
            return true;
        }
        String base = t.endsWith("s") && t.length() > 3 ? t.substring(0, t.length() - 1) : t;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(base) + "s?(?![\\p{L}\\p{N}])")
                .matcher(lowerText).find();
    }

    /** How the resume writes a skill when it uses an alias ("K8s" for Kubernetes). */
    private java.util.Optional<String> surfaceOf(String skill, String text) {
        String canonical = taxonomy.normalize(skill);
        return taxonomy.findMentions(text).stream().filter(m -> m.skill().equals(canonical))
                .map(SkillTaxonomy.Mention::surface).findFirst();
    }

    private static String experienceDetail(ParsedJd jd, BigDecimal years, ResumeContent content) {
        List<String> parts = new ArrayList<>();
        if (jd.minYears() != null) {
            parts.add("Asks for " + jd.minYears() + (jd.maxYears() != null ? "–" + jd.maxYears() : "+") + " years; you have "
                    + (years == null ? "undated roles" : years.stripTrailingZeros().toPlainString() + " years") + ".");
        }
        if (jd.seniority() != null) {
            String currentTitle = content.experiences().isEmpty() ? content.headline()
                    : content.experiences().getFirst().title();
            parts.add("Level asked: " + jd.seniority().name().toLowerCase(Locale.ROOT) + "; yours reads as "
                    + Levels.ofCandidate(currentTitle, years).name().toLowerCase(Locale.ROOT) + ".");
        }
        return parts.isEmpty() ? "The posting doesn't state experience or level." : String.join(" ", parts);
    }

    private static String educationDetail(ParsedJd jd) {
        return "Asks for " + jd.education().level().name().toLowerCase(Locale.ROOT)
                + (jd.education().fields().isEmpty() ? "" : " in " + String.join(" / ", jd.education().fields()))
                + (jd.education().mandatory() ? "" : " (or equivalent experience)") + ".";
    }

    private static String count(int have, int of) {
        return of == 0 ? "The posting lists none." : have + " of " + of + " on your resume.";
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
