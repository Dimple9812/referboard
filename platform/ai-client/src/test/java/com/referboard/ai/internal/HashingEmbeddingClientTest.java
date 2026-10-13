package com.referboard.ai.internal;

import com.referboard.ai.EmbeddingClient;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HashingEmbeddingClientTest {

    private final HashingEmbeddingClient client = new HashingEmbeddingClient(1536, SkillTaxonomy.defaultTaxonomy());

    @Test
    void producesDeterministicUnitVectors() {
        float[] a = client.embed("Senior Java developer with Spring Boot and Kafka");
        float[] b = client.embed("Senior Java developer with Spring Boot and Kafka");

        assertThat(a).hasSize(1536).containsExactly(b);
        assertThat(EmbeddingClient.cosine(a, a)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void sharedSkillsScoreHigherThanUnrelatedText() {
        float[] resume = client.embed("Backend engineer: Java, Spring Boot, microservices, PostgreSQL, K8s");
        float[] backendJob = client.embed("Hiring a Java backend engineer to build Spring Boot microservices on "
                + "Kubernetes with Postgres");
        float[] designJob = client.embed("Visual designer for brand campaigns using Figma and illustration");

        assertThat(EmbeddingClient.cosine(resume, backendJob))
                .isGreaterThan(EmbeddingClient.cosine(resume, designJob) + 0.2);
    }

    @Test
    void blankTextGivesZeroVector() {
        assertThat(client.embed("  ")).containsOnly(0f);
    }
}
