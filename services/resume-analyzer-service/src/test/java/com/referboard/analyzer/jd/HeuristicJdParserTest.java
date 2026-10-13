package com.referboard.analyzer.jd;

import com.referboard.analyzer.Fixtures;
import com.referboard.analyzer.model.ParsedJd;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicJdParserTest {

    final HeuristicJdParser parser = new HeuristicJdParser(SkillTaxonomy.defaultTaxonomy());

    @Test
    void readsSectionsSkillsYearsAndEducation() {
        ParsedJd jd = parser.parse(JdText.normalize(Fixtures.backendJd()), null, "Northwind Payments");
        assertThat(jd.title()).isEqualTo("Senior Backend Engineer (Payments)");
        assertThat(jd.seniority()).isEqualTo(ParsedJd.Seniority.SENIOR);
        assertThat(jd.minYears()).isEqualTo(5);
        assertThat(jd.requiredSkills()).contains("java", "spring boot", "kafka", "postgresql", "kubernetes", "docker",
                "grpc", "rest api", "microservices").doesNotContain("go", "aws", "terraform");
        assertThat(jd.preferredSkills()).containsExactlyInAnyOrder("go", "aws", "terraform");
        assertThat(jd.responsibilities()).hasSize(5).first().asString().startsWith("Design and build");
        assertThat(jd.education().level()).isEqualTo(ParsedJd.DegreeLevel.BACHELORS);
        assertThat(jd.education().mandatory()).as("'or equivalent experience'").isFalse();
        assertThat(jd.education().fields()).contains("computer science");
        assertThat(jd.keywords()).contains("microservices", "event-driven", "low latency", "code review", "fintech",
                "on-call");
    }

    @Test
    void benefitsAndAboutSectionsDontLeakSkillsOrYears() {
        String jd = "Platform Engineer\nAbout us\nWe love Rust and 20 years of history.\nRequirements\n"
                + "- 3+ years with Python\n- Terraform\nBenefits\n- Free Go lessons";
        ParsedJd p = parser.parse(JdText.normalize(jd), null, null);
        assertThat(p.requiredSkills()).containsExactlyInAnyOrder("python", "terraform");
        assertThat(p.minYears()).isEqualTo(3);
    }

    @Test
    void sentencesAreNotMistakenForHeadings() {
        assertThat(HeuristicJdParser.heading("You have 5+ years of Java")).isNull();
        assertThat(HeuristicJdParser.heading("What you'll need")).isEqualTo(HeuristicJdParser.Section.REQUIRED);
        assertThat(HeuristicJdParser.heading("Preferred qualifications:")).isEqualTo(HeuristicJdParser.Section.PREFERRED);
        assertThat(HeuristicJdParser.heading("Key Responsibilities")).isEqualTo(HeuristicJdParser.Section.RESPONSIBILITIES);
        assertThat(HeuristicJdParser.heading("Perks and benefits")).isEqualTo(HeuristicJdParser.Section.OTHER);
    }

    @Test
    void yearRanges() {
        assertThat(HeuristicJdParser.years("3-5 years in backend")).containsExactly(3, 5);
        assertThat(HeuristicJdParser.years("minimum 2+ yrs")).containsExactly(2, null);
        assertThat(HeuristicJdParser.years("founded 120 years ago")).containsExactly(null, null);
    }
}
