package com.referboard.analyzer.prep;

import com.referboard.ai.internal.HashingEmbeddingClient;
import com.referboard.analyzer.Fixtures;
import com.referboard.analyzer.jd.HeuristicJdParser;
import com.referboard.analyzer.jd.JdText;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.prep.PrepItem.Section;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.analyzer.scoring.MatchScorer;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PrepPlannerTest {

    static final SkillTaxonomy TAXONOMY = SkillTaxonomy.defaultTaxonomy();
    static final QuestionBank BANK = QuestionBank.load();
    final ParsedJd jd = new HeuristicJdParser(TAXONOMY).parse(JdText.normalize(Fixtures.backendJd()), null, "Northwind");
    final ResumeContent resume = Fixtures.resume();
    final MatchReport report = new MatchScorer(TAXONOMY, new HashingEmbeddingClient(1536, TAXONOMY),
            Clock.systemUTC()).score(resume, resume.toPlainText(), 1, jd);
    final List<PrepItem> plan = new PrepPlanner(BANK, TAXONOMY).plan(resume, jd, report.matchedSkills(),
            report.missingSkills(), new BigDecimal("7.5"), "Senior Backend Engineer", "Northwind");

    Map<Section, List<PrepItem>> bySection() {
        return plan.stream().collect(Collectors.groupingBy(PrepItem::section));
    }

    @Test
    void everySkillInTheTaxonomyGetsAQuestion() {
        for (SkillTaxonomy.Skill s : TAXONOMY.all()) {
            assertThat(BANK.forSkill(s.name(), s.category(), s.display())).as(s.name()).isNotEmpty()
                    .allSatisfy(q -> assertThat(q.q()).doesNotContain("{skill}").matches(".*[?.]$"));
        }
        // Bank keys must be real canonical skills, or they'd never be used.
        assertThat(BANK.skillKeys()).allSatisfy(k -> assertThat(TAXONOMY.normalize(k)).as(k).isEqualTo(k));
        assertThat(BANK.skillKeys()).allSatisfy(k -> assertThat(TAXONOMY.isKnown(k)).as(k).isTrue());
        BANK.toAsk().forEach(q -> assertThat(q.q()).endsWith("?"));
    }

    @Test
    void planHasEverySectionInOrderWithUniqueIds() {
        assertThat(plan.stream().map(PrepItem::section).distinct().toList())
                .containsExactly(Section.EXPERIENCE, Section.TECHNICAL, Section.ROLE, Section.BEHAVIOURAL, Section.GAPS, Section.ASK);
        assertThat(new HashSet<>(plan.stream().map(PrepItem::id).toList())).hasSameSizeAs(plan);
    }

    @Test
    void experienceQuestionsQuoteTheResumeAndAskHowMetricsWereMeasured() {
        List<PrepItem> exp = bySection().get(Section.EXPERIENCE);
        assertThat(exp).hasSizeLessThanOrEqualTo(PrepPlanner.MAX_EXPERIENCE)
                .allSatisfy(i -> assertThat(resume.toPlainText()).contains(i.example().text()));
        PrepItem latency = exp.stream().filter(i -> i.example().text().startsWith("Cut p99 latency")).findFirst().orElseThrow();
        assertThat(latency.question()).isEqualTo("Your resume says: “Cut p99 latency of the payouts API by 40% by adding "
                + "Redis caching”. How did you do it, and how did you measure the result?");
        assertThat(latency.pointers()).anyMatch(p -> p.startsWith("The result: 40%"));
        assertThat(latency.example().company()).isEqualTo("Lumen Pay");
    }

    @Test
    void technicalQuestionsAreForSkillsOnTheResumeAndGapsForTheRest() {
        List<PrepItem> tech = bySection().get(Section.TECHNICAL);
        assertThat(tech).isNotEmpty().hasSizeLessThanOrEqualTo(PrepPlanner.MAX_TECHNICAL);
        assertThat(tech).extracting(PrepItem::skill).contains("Java", "Spring Boot").doesNotContain("gRPC", "Go");
        assertThat(tech.getFirst().skill()).isEqualTo("Java");
        assertThat(tech).filteredOn(i -> i.skill().equals("Java")).hasSize(2);
        assertThat(tech).filteredOn(i -> i.skill().equals("Java")).first()
                .satisfies(i -> assertThat(i.pointers()).anyMatch(p -> p.startsWith("Use your own example: you used Java as")));

        List<PrepItem> gaps = bySection().get(Section.GAPS);
        assertThat(gaps).extracting(PrepItem::question).contains("Have you worked with gRPC?");
        PrepItem grpc = gaps.stream().filter(g -> g.skill().equals("gRPC")).findFirst().orElseThrow();
        assertThat(grpc.why()).startsWith("It's a requirement");
        assertThat(grpc.pointers().getFirst()).contains("Be honest");
        assertThat(grpc.pointers()).anyMatch(p -> p.startsWith("If they go deeper: “When would you pick gRPC over REST"));
    }

    @Test
    void behaviouralQuestionsMatchTheLevel() {
        List<String> senior = bySection().get(Section.BEHAVIOURAL).stream().map(PrepItem::question).toList();
        assertThat(String.join(" ", senior)).contains("mentored");
        assertThat(PrepPlanner.level(jd, new BigDecimal("0.5"))).isIn("SENIOR", "LEAD");   // the posting decides first
        ParsedJd noLevel = new ParsedJd(jd.title(), jd.company(), null, null, null, jd.requiredSkills(),
                jd.preferredSkills(), jd.responsibilities(), jd.keywords(), jd.education(), jd.location(), jd.remote());
        assertThat(PrepPlanner.level(noLevel, new BigDecimal("1.5"))).isEqualTo("ENTRY");
        assertThat(PrepPlanner.level(noLevel, new BigDecimal("6"))).isEqualTo("SENIOR");
    }

    @Test
    void readsTheFiguresALineClaims() {
        assertThat(PrepPlanner.numbers("Cut p99 latency by 40% for 2M transactions in 2021"))
                .containsExactly("40%", "2M transactions");
        assertThat(PrepPlanner.numbers("Mentored 3 engineers")).containsExactly("3 engineers");
        assertThat(PrepPlanner.numbers("Led the migration")).isEmpty();
        assertThat(PrepPlanner.numbers("Split 12 services and cut deploys to 200 ms")).containsExactly("12", "200 ms");
    }

    @Test
    void aiQuestionsAreFilteredAndDeduplicated() {
        String jdText = "We process 10,000 payouts a day on Kafka and PostgreSQL.";
        List<PrepItem> kept = AiPrepWriter.accept(List.of(
                new AiPrepWriter.AiQuestion("How would you design payouts to handle 10,000 a day without double paying?",
                        "Idempotency at scale.", List.of("Idempotency keys", "Reconciliation")),
                new AiPrepWriter.AiQuestion("How would you scale to 5 million payouts?", "Invented number.", List.of()),
                new AiPrepWriter.AiQuestion("Tell me about Kafka", "Not a question.", List.of()),
                new AiPrepWriter.AiQuestion("What is the GIL?", "Too short a question to keep?", List.of()),
                new AiPrepWriter.AiQuestion("Have you worked with gRPC?", "Duplicate of the plan.", List.of())),
                jdText, plan);
        assertThat(kept).extracting(PrepItem::question)
                .containsExactly("How would you design payouts to handle 10,000 a day without double paying?");
        assertThat(kept.getFirst()).satisfies(i -> {
            assertThat(i.source()).isEqualTo(PrepItem.Source.AI);
            assertThat(i.section()).isEqualTo(Section.ROLE);
            assertThat(i.id()).isEqualTo("ai-1");
        });
    }
}
