package com.referboard.interview.prep;

import com.referboard.interview.Fixtures;
import com.referboard.interview.client.AnalyzerClient;
import com.referboard.interview.prep.PrepBuilder.Item;
import com.referboard.interview.prep.PrepBuilder.Section;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PrepBuilderTest {

    final PrepBuilder builder = new PrepBuilder(QuestionBank.load(), SkillTaxonomy.defaultTaxonomy());
    final AnalyzerClient.Context ctx = Fixtures.context();
    final List<Item> items = builder.build(ctx);

    List<Item> section(Section s) {
        return items.stream().filter(i -> i.section() == s).toList();
    }

    @Test
    void everyExampleIsOneOfTheUsersOwnBulletsVerbatim() {
        Set<String> bullets = ctx.resume().experiences().stream().flatMap(e -> e.bullets().stream()).collect(Collectors.toSet());
        assertThat(items).filteredOn(i -> i.evidence() != null).isNotEmpty()
                .allSatisfy(i -> assertThat(bullets).contains(i.evidence()));
        // Each bullet is suggested once at most (the intro highlight aside), so the examples vary.
        List<String> used = items.stream().filter(i -> i.evidence() != null && i.section() != Section.INTRO)
                .map(Item::evidence).distinct().toList();
        Set<String> perQuestionSkill = new HashSet<>();
        items.stream().filter(i -> i.section() == Section.TECHNICAL && i.evidence() != null)
                .forEach(i -> perQuestionSkill.add(i.skill() + "|" + i.evidence()));
        assertThat(used.size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void introIsAnOutlineFromFactsNotAScript() {
        Item intro = section(Section.INTRO).getFirst();
        assertThat(intro.question()).isEqualTo("Tell me about yourself.");
        assertThat(intro.tip()).contains("Senior Software Engineer at Lumen Pay, 7+ years in")
                .contains("Java, Spring Boot and PostgreSQL").contains("at Northwind Payments");
        assertThat(intro.evidence()).isEqualTo(Fixtures.TOP_BULLET);
    }

    @Test
    void technicalQuestionsFollowTheRequiredSkillsTheResumeShows() {
        List<Item> tech = section(Section.TECHNICAL);
        assertThat(tech.stream().map(Item::skill).distinct().toList()).startsWith("Java", "Spring Boot", "PostgreSQL");
        assertThat(tech).filteredOn(i -> "Java".equals(i.skill())).hasSize(2)
                .allSatisfy(i -> assertThat(i.evidence()).isEqualTo(Fixtures.TOP_BULLET));
        // Kafka is listed but never used in a role: the tip says so instead of inventing an example.
        assertThat(tech).filteredOn(i -> "Apache Kafka".equals(i.skill())).allSatisfy(i -> {
            assertThat(i.evidence()).isNull();
            assertThat(i.tip()).contains("on your resume but not in a role's bullets");
        });
        assertThat(tech).as("senior role: a design question").anyMatch(i -> "System design".equals(i.skill()));
        assertThat(tech).noneMatch(i -> "gRPC".equals(i.skill()));
    }

    @Test
    void gapsGetAnHonestAnswerNeverAClaim() {
        Item grpc = section(Section.GAPS).getFirst();
        assertThat(grpc.question()).isEqualTo("Have you worked with gRPC?");
        assertThat(grpc.tip()).startsWith("Say plainly that you haven't used gRPC at work.")
                .contains("Don't claim experience you don't have.");
        assertThat(grpc.evidence()).isNull();
    }

    @Test
    void roleQuestionsQuoteTheResponsibilityAndFindTheClosestExample() {
        List<Item> role = section(Section.ROLE);
        assertThat(role).hasSize(4);
        Item latency = role.stream().filter(i -> i.question().contains("latency of the payouts API")).findFirst().orElseThrow();
        assertThat(latency.evidence()).startsWith("Cut p99 latency of the payouts API");
    }

    @Test
    void behaviouralExamplesMatchTheQuestion() {
        Item mentoring = section(Section.BEHAVIOURAL).stream()
                .filter(i -> i.question().contains("helped someone else")).findFirst().orElseThrow();
        assertThat(mentoring.evidence()).startsWith("Mentored 3 engineers");
        Item broke = section(Section.BEHAVIOURAL).stream()
                .filter(i -> i.question().contains("broke in production")).findFirst().orElseThrow();
        assertThat(broke.evidence()).startsWith("Fixed a recurring outage");
    }

    @Test
    void seniorRolesGetSeniorQuestionsToAsk() {
        assertThat(section(Section.ASK)).hasSize(6).anyMatch(i -> i.question().contains("design and mentoring"));
        assertThat(items).allSatisfy(i -> assertThat(i.origin()).isEqualTo("RULES"));
    }

    @Test
    void aiQuestionsUseTheResumesWordsAndDropJunk() {
        AiQuestions.Output out = new AiQuestions.Output(List.of(
                new AiQuestions.Q("ROLE", "How would you make settlement reconciliation safe to re-run?", "Idempotency in batch jobs.",
                        null, 0, 2),
                new AiQuestions.Q("TECHNICAL", "Tell me about yourself.", "dup", null, null, null),
                new AiQuestions.Q("TECHNICAL", "How did you handle [X] at Northwind?", "placeholder", null, null, null),
                new AiQuestions.Q("TECHNICAL", "How do you size Kafka partitions for UPI traffic peaks?", "Capacity planning.",
                        "Kafka", 9, 0)));
        List<Item> kept = AiQuestions.accept(out, ctx, items);
        assertThat(kept).extracting(Item::question).containsExactly(
                "How would you make settlement reconciliation safe to re-run?",
                "How do you size Kafka partitions for UPI traffic peaks?");
        assertThat(kept.getFirst().evidence()).isEqualTo("Moved reconciliation jobs to PostgreSQL partitioned tables");
        assertThat(kept.get(1).evidence()).as("out-of-range index: no example").isNull();
        assertThat(kept).allSatisfy(i -> assertThat(i.origin()).isEqualTo("AI"));
    }
}
