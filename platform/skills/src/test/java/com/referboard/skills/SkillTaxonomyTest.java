package com.referboard.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillTaxonomyTest {

    private final SkillTaxonomy taxonomy = SkillTaxonomy.defaultTaxonomy();

    @Test
    void normalizesAliasesToCanonicalNames() {
        assertThat(taxonomy.normalize("K8s")).isEqualTo("kubernetes");
        assertThat(taxonomy.normalize(" JS ")).isEqualTo("javascript");
        assertThat(taxonomy.normalize("ReactJS")).isEqualTo("react");
        assertThat(taxonomy.normalize("Springboot")).isEqualTo("spring boot");
        assertThat(taxonomy.normalize("Golang")).isEqualTo("go");
        assertThat(taxonomy.normalize("Go")).as("strict words still normalise in structured lists").isEqualTo("go");
        assertThat(taxonomy.normalize("Postgres")).isEqualTo("postgresql");
        assertThat(taxonomy.normalize("Some Niche Tool")).isEqualTo("some niche tool");
        assertThat(taxonomy.normalize("  ")).isNull();
    }

    @Test
    void displayNamesForUi() {
        assertThat(taxonomy.display("k8s")).isEqualTo("Kubernetes");
        assertThat(taxonomy.display("unknown thing")).isEqualTo("unknown thing");
    }

    @Test
    void extractsSkillsFromFreeText() {
        String bullet = "Built Spring Boot microservices on AWS with Kafka and PostgreSQL; deployed via K8s and "
                + "GitHub Actions. Frontend in React + TypeScript.";

        assertThat(taxonomy.extract(bullet)).containsExactly(
                "spring boot", "microservices", "aws", "kafka", "postgresql", "kubernetes", "github actions",
                "react", "typescript");
    }

    @Test
    void ambiguousWordsOnlyMatchWhenWrittenExactly() {
        assertThat(taxonomy.extract("We go to great lengths; plan c is fine; excel at teamwork; R&D budget"))
                .isEmpty();
        assertThat(taxonomy.extract("Services written in Go and C, analysis in R, reports in Excel."))
                .containsExactly("go", "c", "r", "excel");
    }

    @Test
    void longerSkillsWinOverTheirSubstrings() {
        assertThat(taxonomy.extract("Node.js, C++, C#, .NET Core and Spring Cloud"))
                .containsExactly("node.js", "c++", "c#", ".net", "spring cloud");
        assertThat(taxonomy.extract("C-level stakeholders")).isEmpty();
    }

    @Test
    void reportsWhereEachSkillAppears() {
        List<SkillTaxonomy.Mention> mentions = taxonomy.findMentions("Led a team using Java 21");
        assertThat(mentions).singleElement().satisfies(m -> {
            assertThat(m.skill()).isEqualTo("java");
            assertThat(m.surface()).isEqualTo("Java");
            assertThat(m.start()).isEqualTo(17);
        });
    }

    @Test
    void rejectsAliasCollisions() {
        var a = new SkillTaxonomy.Skill("alpha", "Alpha", "x", List.of("shared"), List.of());
        var b = new SkillTaxonomy.Skill("beta", "Beta", "x", List.of("shared"), List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SkillTaxonomy.of(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared");
    }
}
