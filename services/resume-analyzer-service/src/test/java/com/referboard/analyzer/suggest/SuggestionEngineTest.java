package com.referboard.analyzer.suggest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.ai.LlmClient;
import com.referboard.ai.internal.HashingEmbeddingClient;
import com.referboard.analyzer.Fixtures;
import com.referboard.analyzer.jd.HeuristicJdParser;
import com.referboard.analyzer.jd.JdText;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.analyzer.scoring.MatchScorer;
import com.referboard.analyzer.suggest.Suggestion.Type;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuggestionEngineTest {

    static final SkillTaxonomy TAXONOMY = SkillTaxonomy.defaultTaxonomy();
    final LlmClient llm = mock(LlmClient.class);
    final SuggestionEngine engine = new SuggestionEngine(llm, TAXONOMY, new ObjectMapper());
    final ParsedJd jd = new HeuristicJdParser(TAXONOMY).parse(JdText.normalize(Fixtures.backendJd()), null, "Northwind");
    final ResumeContent resume = Fixtures.resume();
    final MatchReport report = new MatchScorer(TAXONOMY, new HashingEmbeddingClient(1536, TAXONOMY),
            Clock.systemUTC()).score(resume, resume.toPlainText(), 1, jd);

    @Test
    void rulesWithoutAnLlm() {
        when(llm.available()).thenReturn(false);
        var out = engine.suggest(resume, jd, report, new BigDecimal("7.5"));
        assertThat(out.aiUsed()).isFalse();
        assertThat(out.notices()).anyMatch(n -> n.contains("bullet rewrites are not suggested"));
        List<Suggestion> s = out.suggestions();

        Suggestion wording = only(s, Type.SKILL_WORDING);
        assertThat(wording.before()).isEqualTo("K8s");
        assertThat(wording.after()).isEqualTo("Kubernetes (K8s)");
        assertThat(wording.requiresConfirmation()).as("same skill, no new claim").isFalse();

        Suggestion reorder = only(s, Type.SKILLS_REORDER);
        assertThat(reorder.target().skillsOrder()).startsWith("Java", "Spring Boot", "Kafka", "PostgreSQL")
                .endsWith("Redis", "Python");

        List<Suggestion> adds = s.stream().filter(x -> x.type() == Type.ADD_SKILL).toList();
        // gRPC, REST APIs and microservices are all named in the requirements; none is on the resume.
        assertThat(adds).extracting(Suggestion::after).containsExactly("gRPC", "REST APIs", "Microservices", "Go", "AWS",
                "Terraform");
        assertThat(adds).allSatisfy(a -> {
            assertThat(a.requiresConfirmation()).isTrue();
            assertThat(a.confirmationLabel()).isEqualTo("Add only if you have this experience");
        });
        assertThat(adds.getFirst().priority()).isEqualTo(Suggestion.Priority.HIGH);

        Suggestion summary = only(s, Type.SUMMARY_REWRITE);
        assertThat(summary.after()).startsWith("Senior Software Engineer with 7+ years of experience in Java")
                .endsWith("currently at Lumen Pay.");
        assertThat(only(s, Type.EVIDENCE_ADVICE).rationale()).contains("Kafka");
        assertThat(only(s, Type.GAP_ADVICE).title()).contains("gRPC");
        assertThat(s.stream().filter(x -> x.type() == Type.SENIORITY_ADVICE)).as("7.5 years ≥ 5").isEmpty();
    }

    @Test
    void keepsTheUsersSummaryWhenARewriteWouldNameNoSkillsTheRoleWants() {
        when(llm.available()).thenReturn(false);
        ParsedJd design = new HeuristicJdParser(TAXONOMY).parse(JdText.normalize("""
                Product Designer

                Requirements
                - Hands-on experience with Figma
                - Hands-on experience with A/B testing
                """), null, "Banyan");
        ResumeContent withSummary = resume.withSummary("Backend engineer building payment platforms on Java.");
        MatchReport weak = new MatchScorer(TAXONOMY, new HashingEmbeddingClient(1536, TAXONOMY), Clock.systemUTC())
                .score(withSummary, withSummary.toPlainText(), 1, design);
        assertThat(weak.matchedSkills()).isEmpty();
        var out = engine.suggest(withSummary, design, weak, new BigDecimal("7.5"));
        assertThat(out.suggestions()).noneMatch(x -> x.type() == Type.SUMMARY_REWRITE);

        // With no summary at all, adding one is still useful.
        var blank = engine.suggest(resume, design, weak, new BigDecimal("7.5"));
        assertThat(blank.suggestions()).anyMatch(x -> x.type() == Type.SUMMARY_REWRITE && x.title().equals("Add a short summary"));
    }

    @Test
    void aiRewritesAreGuarded() {
        when(llm.available()).thenReturn(true);
        when(llm.structured(any())).thenReturn(new SuggestionEngine.AiOutput(List.of(
                new SuggestionEngine.BulletRewrite(0, 1, "Reduced payouts API p99 latency by 40% with Redis caching",
                        "Leads with the result"),
                new SuggestionEngine.BulletRewrite(0, 0, "Built event-driven Spring Boot microservices in Java on Kafka "
                        + "for UPI settlements processing 2M transactions a day", "Uses the posting's terms"),
                new SuggestionEngine.BulletRewrite(0, 3, "Mentored 5 engineers", "inflated"),
                new SuggestionEngine.BulletRewrite(9, 0, "out of range", "bad index")),
                "Senior Software Engineer with 7+ years building Java and Spring Boot payment services at Lumen Pay.",
                "gRPC is required and missing.", null));
        var out = engine.suggest(resume, jd, report, new BigDecimal("7.5"));
        assertThat(out.aiUsed()).isTrue();
        List<Suggestion> rewrites = out.suggestions().stream().filter(x -> x.type() == Type.BULLET_REWRITE).toList();
        assertThat(rewrites).hasSize(2);

        Suggestion clean = rewrites.getFirst();
        assertThat(clean.before()).isEqualTo("Cut p99 latency of the payouts API by 40% by adding Redis caching");
        assertThat(clean.requiresConfirmation()).isFalse();

        Suggestion claim = rewrites.get(1);
        assertThat(claim.skillsIntroduced()).containsExactlyInAnyOrder("microservices", "kafka");
        assertThat(claim.requiresConfirmation()).as("Kafka and microservices weren't in that bullet").isTrue();
        assertThat(claim.rationale()).isEqualTo("Uses the posting's terms. Mentions Microservices and Apache Kafka, "
                + "which the original bullet doesn't.");

        assertThat(out.suggestions().stream().filter(x -> x.type() == Type.SUMMARY_REWRITE))
                .singleElement().satisfies(x -> assertThat(x.origin()).isEqualTo(Suggestion.Origin.AI));
        assertThat(out.suggestions().stream().filter(x -> x.type() == Type.GAP_ADVICE))
                .singleElement().satisfies(x -> assertThat(x.rationale()).isEqualTo("gRPC is required and missing."));
    }

    @Test
    void aiFailureFallsBackToRules() {
        when(llm.available()).thenReturn(true);
        when(llm.structured(any())).thenThrow(new RuntimeException("boom"));
        var out = engine.suggest(resume, jd, report, new BigDecimal("7.5"));
        assertThat(out.aiUsed()).isFalse();
        assertThat(out.suggestions()).isNotEmpty();
        assertThat(out.notices()).anyMatch(n -> n.contains("unavailable right now"));
    }

    static Suggestion only(List<Suggestion> s, Type type) {
        return s.stream().filter(x -> x.type() == type).findFirst().orElseThrow(() -> new AssertionError("no " + type));
    }
}
