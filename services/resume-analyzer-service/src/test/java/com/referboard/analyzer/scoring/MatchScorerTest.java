package com.referboard.analyzer.scoring;

import com.referboard.ai.internal.HashingEmbeddingClient;
import com.referboard.analyzer.Fixtures;
import com.referboard.analyzer.jd.HeuristicJdParser;
import com.referboard.analyzer.jd.JdText;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.scoring.MatchReport.CheckStatus;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScorerTest {

    static final SkillTaxonomy TAXONOMY = SkillTaxonomy.defaultTaxonomy();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    final MatchScorer scorer = new MatchScorer(TAXONOMY, new HashingEmbeddingClient(1536, TAXONOMY), CLOCK);
    final ParsedJd jd = new HeuristicJdParser(TAXONOMY).parse(JdText.normalize(Fixtures.backendJd()), null, "Northwind");

    @Test
    void scoresEvidenceNotJustKeywords() {
        ResumeContent resume = Fixtures.resume();
        MatchReport r = scorer.score(resume, resume.toPlainText(), 1, jd);

        Map<String, MatchReport.SkillMatch> matched = r.matchedSkills().stream()
                .collect(Collectors.toMap(MatchReport.SkillMatch::name, Function.identity()));
        assertThat(matched.get("java").evidence()).isEqualTo(SkillEvidence.Level.STRONG);
        assertThat(matched.get("java").usedIn()).isEqualTo("Senior Software Engineer at Lumen Pay");
        assertThat(matched.get("kafka").evidence()).as("listed only").isEqualTo(SkillEvidence.Level.WEAK);
        assertThat(matched.get("kubernetes").evidence()).as("'K8s' is the same skill").isEqualTo(SkillEvidence.Level.WEAK);
        assertThat(r.missingSkills().required()).extracting(MatchReport.SkillRef::name)
                .containsExactly("grpc", "rest api", "microservices");
        assertThat(r.missingSkills().preferred()).extracting(MatchReport.SkillRef::name)
                .containsExactlyInAnyOrder("go", "aws", "terraform");
        assertThat(r.missingSkills().weaklyEvidenced()).extracting(MatchReport.SkillRef::name)
                .contains("kafka", "kubernetes");

        assertThat(r.breakdown()).extracting(MatchReport.Component::weight).containsExactly(40, 15, 15, 15, 10, 5);
        assertThat(r.breakdown().stream().mapToDouble(MatchReport.Component::points).sum())
                .isCloseTo(r.score(), org.assertj.core.data.Offset.offset(1.0));
        assertThat(r.score()).isBetween(55, 85);
        assertThat(component(r, "experience").score()).as("7.5 years vs 5+, senior vs senior").isEqualTo(1.0);
        assertThat(component(r, "education").score()).isEqualTo(1.0);
    }

    @Test
    void aliasWordingIsFlaggedForAts() {
        ResumeContent resume = Fixtures.resume();
        MatchReport r = scorer.score(resume, resume.toPlainText(), 1, jd);
        MatchReport.AtsCheck wording = check(r, "SKILL_WORDING");
        assertThat(wording.status()).isEqualTo(CheckStatus.WARN);
        assertThat(wording.detail()).contains("\"Kubernetes\" (you wrote \"K8s\")");
        assertThat(check(r, "CONTACT_INFO").status()).isEqualTo(CheckStatus.PASS);
        assertThat(check(r, "LENGTH").status()).isEqualTo(CheckStatus.PASS);
        assertThat(check(r, "SECTION_HEADINGS").status()).isEqualTo(CheckStatus.PASS);
    }

    @Test
    void silenceInTheJdNeverCostsPoints() {
        ParsedJd bare = new ParsedJd("Backend Engineer", null, null, null, null, List.of("java"), List.of(), List.of(),
                List.of(), null, null, null);
        ResumeContent resume = Fixtures.resume();
        MatchReport r = scorer.score(resume, resume.toPlainText(), 1, bare);
        assertThat(component(r, "preferredSkills").score()).isNull();
        assertThat(component(r, "experience").score()).isNull();
        assertThat(component(r, "education").score()).isNull();
        assertThat(component(r, "responsibilities").score()).isNull();
        assertThat(r.score()).as("only required skills + ATS remain, rescaled").isGreaterThanOrEqualTo(85);
    }

    @Test
    void experienceShortfallAndLevelGap() {
        ParsedJd staff = new ParsedJd("Staff Engineer", null, ParsedJd.Seniority.STAFF, 10, null, List.of(), List.of(),
                List.of(), List.of(), null, null, null);
        double s = MatchScorer.experience(staff, Fixtures.resume(), new java.math.BigDecimal("7.5"));
        assertThat(s).isCloseTo(0.65 * 0.75 + 0.35 * 0.7, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void weakResumeScoresLow() {
        ResumeContent thin = new ResumeContent("A", null, null, null, null, null, List.of("Excel"),
                List.of(new ResumeContent.Experience("Shop", "Sales Associate", null, "2024-01", null, true,
                        List.of("Helped customers"))), List.of(), List.of(), List.of());
        MatchReport r = scorer.score(thin, thin.toPlainText(), 1, jd);
        assertThat(r.score()).isLessThan(30);
        assertThat(r.verdict()).isEqualTo(MatchReport.Verdict.WEAK);
        assertThat(check(r, "CONTACT_INFO").status()).isEqualTo(CheckStatus.FAIL);
    }

    @Test
    void termMatchingIsWholeWordAndPluralTolerant() {
        assertThat(MatchScorer.containsTerm("built rest apis for payments", "rest api")).isTrue();
        assertThat(MatchScorer.containsTerm("golang and gopher", "go")).isFalse();
        assertThat(MatchScorer.containsTerm("ci/cd pipelines", "ci/cd")).isTrue();
    }

    static MatchReport.Component component(MatchReport r, String key) {
        return r.breakdown().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    static MatchReport.AtsCheck check(MatchReport r, String id) {
        return r.atsChecks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
