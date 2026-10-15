package com.referboard.analyzer.letter;

import com.referboard.ai.internal.HashingEmbeddingClient;
import com.referboard.analyzer.Fixtures;
import com.referboard.analyzer.jd.HeuristicJdParser;
import com.referboard.analyzer.jd.JdText;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.scoring.MatchReport;
import com.referboard.analyzer.scoring.MatchScorer;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class CoverLetterTest {

    static final SkillTaxonomy TAXONOMY = SkillTaxonomy.defaultTaxonomy();
    final ParsedJd jd = new HeuristicJdParser(TAXONOMY).parse(JdText.normalize(Fixtures.backendJd()), null, "Northwind");
    final ResumeContent resume = Fixtures.resume();
    final MatchReport report = new MatchScorer(TAXONOMY, new HashingEmbeddingClient(1536, TAXONOMY),
            Clock.systemUTC()).score(resume, resume.toPlainText(), 1, jd);

    LetterFacts facts(String motivation) {
        return LetterFacts.from(resume, jd, report.matchedSkills(), "7+", "Senior Backend Engineer", "Northwind",
                motivation, TAXONOMY);
    }

    @Test
    void evidenceIsTheUsersOwnBulletsThatShowWhatTheJobWants() {
        LetterFacts f = facts(null);
        assertThat(f.evidence()).isNotEmpty();
        assertThat(f.evidence()).allSatisfy(e -> assertThat(resume.toPlainText()).contains(e.bullet()));
        // The strongest (Java + Spring Boot, a measured result) is chosen; examples are in date order, newest first.
        assertThat(f.evidence()).anyMatch(e -> e.bullet().startsWith("Built Spring Boot services in Java"));
        assertThat(f.evidence()).isSortedAccordingTo(java.util.Comparator.comparingInt(LetterFacts.Evidence::experienceIndex));
        assertThat(f.evidence()).noneMatch(e -> e.bullet().startsWith("Mentored"));
    }

    @Test
    void standardLetterSaysOnlyWhatTheResumeSays() {
        String letter = TemplateLetterWriter.write(facts(null), LetterLength.STANDARD);
        assertThat(letter).startsWith("Dear Hiring Team at Northwind,\n\n"
                + "I'm applying for the Senior Backend Engineer role at Northwind. "
                + "I'm a Senior Software Engineer with 7+ years of experience, currently at Lumen Pay.");
        assertThat(letter).contains("At Lumen Pay, I built Spring Boot services in Java for UPI settlements processing "
                + "2M transactions a day.");
        assertThat(letter).endsWith("Regards,\nAsha Rao");
        assertThat(letter).doesNotContain("[").doesNotContain("passionate");
        // The template passes the same guard an AI letter must pass.
        assertThat(LetterGuard.check(letter, facts(null), LetterLength.STANDARD, TAXONOMY).ok()).isTrue();
    }

    @Test
    void examplesFromOlderRolesComeAfterNewerOnes() {
        LetterFacts f = facts(null);
        LetterFacts flipped = new LetterFacts(f.fullName(), f.email(), f.phone(), f.jobTitle(), f.company(), f.currentTitle(),
                f.currentCompany(), f.yearsText(), f.matchedSkills(), java.util.List.of(
                new LetterFacts.Evidence(0, "Lumen Pay", "SSE", "Built payout APIs in Java", java.util.List.of("Java")),
                new LetterFacts.Evidence(1, "Flipkart", "SDE II", "Developed order APIs", java.util.List.of("Java"))),
                null, f.resumeText());
        assertThat(TemplateLetterWriter.examples(flipped, 2, true))
                .isEqualTo("At Lumen Pay, I built payout APIs in Java. Before that, at Flipkart, I developed order APIs.");
    }

    @Test
    void motivationIsUsedAsWrittenAndNeverInvented() {
        assertThat(TemplateLetterWriter.write(facts(null), LetterLength.STANDARD)).doesNotContainIgnoringCase("why");
        String withNote = TemplateLetterWriter.write(facts("I use Northwind's API at work and like how it's designed"),
                LetterLength.STANDARD);
        assertThat(withNote).contains("\n\nI use Northwind's API at work and like how it's designed.\n\n");
    }

    @Test
    void shortLetterFitsAnEmail() {
        String letter = TemplateLetterWriter.write(facts(null), LetterLength.SHORT);
        assertThat(letter).contains("currently at Lumen Pay. There, I built Spring Boot services");
        assertThat(letter.split("\\s+").length).isLessThan(LetterLength.SHORT.maxWords());
        assertThat(letter.split("\n\n")).hasSize(3);
        assertThat(LetterGuard.check(letter, facts(null), LetterLength.SHORT, TAXONOMY).ok()).isTrue();
    }

    @Test
    void bulletsBecomeFirstPersonSentencesOnlyWhenTheyStartWithAnAction() {
        assertThat(TemplateLetterWriter.asSentence("• Cut p99 latency by 40%.")).isEqualTo("I cut p99 latency by 40%.");
        assertThat(TemplateLetterWriter.asSentence("Migrated billing to Kafka")).isEqualTo("I migrated billing to Kafka.");
        assertThat(TemplateLetterWriter.asSentence("Distributed tracing rollout")).isNull();
        assertThat(TemplateLetterWriter.asSentence("Tech: Java, Kafka")).isNull();
        assertThat(TemplateLetterWriter.article("SDE II")).isEqualTo("an");
        assertThat(TemplateLetterWriter.article("Senior Engineer")).isEqualTo("a");
        assertThat(TemplateLetterWriter.article("Engineering Manager")).isEqualTo("an");
    }

    @Test
    void guardRejectsInventedNumbersSkillsPromisesAndPlaceholders() {
        LetterFacts f = facts(null);
        String ok = "Dear Hiring Team at Northwind,\n\nI'm applying for the Senior Backend Engineer role. At Lumen Pay "
                + "I cut p99 latency of the payouts API by 40% with Redis caching.\n\nRegards,\nAsha Rao";
        assertThat(LetterGuard.check(ok, f, LetterLength.STANDARD, TAXONOMY).ok()).isTrue();
        assertThat(LetterGuard.check(ok.replace("40%", "60%"), f, LetterLength.STANDARD, TAXONOMY).reason())
                .contains("number not in source");
        assertThat(LetterGuard.check(ok.replace("Redis caching", "Redis caching on AWS"), f, LetterLength.STANDARD,
                TAXONOMY).reason()).contains("claims skills not on the resume");
        assertThat(LetterGuard.check(ok.replace("Regards", "References available on request.\n\nRegards"), f,
                LetterLength.STANDARD, TAXONOMY).ok()).isFalse();
        assertThat(LetterGuard.check(ok.replace("Northwind,", "[Company],"), f, LetterLength.STANDARD, TAXONOMY).ok())
                .isFalse();
        assertThat(LetterGuard.check(ok.replace("Asha Rao", "Your name"), f, LetterLength.STANDARD, TAXONOMY).reason())
                .contains("not signed");
        assertThat(LetterGuard.check(ok.replace("Dear Hiring Team at Northwind,\n\n", ""), f, LetterLength.STANDARD,
                TAXONOMY).reason()).isEqualTo("no greeting");
        assertThat(LetterGuard.check(ok + " word".repeat(200), f, LetterLength.SHORT, TAXONOMY).reason())
                .startsWith("too long");
    }

    @Test
    void cleansModelWrappers() {
        assertThat(AiLetterWriter.clean("Here's your letter:\n\n```\nDear Hiring Manager,\n\n**Hi**\n\n\n\nRegards,\nA\n```"))
                .isEqualTo("Dear Hiring Manager,\n\nHi\n\nRegards,\nA");
    }
}
