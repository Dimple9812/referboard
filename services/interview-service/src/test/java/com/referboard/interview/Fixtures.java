package com.referboard.interview;

import com.referboard.interview.client.AnalyzerClient;

import java.util.List;
import java.util.UUID;

public final class Fixtures {

    private Fixtures() {
    }

    public static final String TOP_BULLET = "Built Spring Boot services in Java for UPI settlements processing 2M transactions a day";

    public static AnalyzerClient.Context context() {
        return new AnalyzerClient.Context(UUID.randomUUID(),
                new AnalyzerClient.Job(UUID.randomUUID(), "Senior Backend Engineer", "Northwind Payments"),
                new AnalyzerClient.Jd("Senior Backend Engineer", "Northwind Payments", "SENIOR", 5,
                        List.of("java", "spring boot", "kafka", "postgresql", "grpc"), List.of("kubernetes"),
                        List.of("Build payment services in Java and Spring Boot",
                                "Own Kafka consumers and the reliability of settlement pipelines",
                                "Improve the latency of the payouts API", "Mentor engineers on the team")),
                List.of(new AnalyzerClient.SkillMatch("java", "Java", true, "STRONG", "Senior Software Engineer at Lumen Pay"),
                        new AnalyzerClient.SkillMatch("spring boot", "Spring Boot", true, "STRONG", "Senior Software Engineer at Lumen Pay"),
                        new AnalyzerClient.SkillMatch("postgresql", "PostgreSQL", true, "STRONG", "Senior Software Engineer at Lumen Pay"),
                        new AnalyzerClient.SkillMatch("kafka", "Apache Kafka", true, "WEAK", null)),
                new AnalyzerClient.Missing(List.of(new AnalyzerClient.SkillRef("grpc", "gRPC")), List.of()),
                "7+",
                new AnalyzerClient.Resume("Asha Rao", "Backend engineer, payments", null,
                        List.of("Java", "Spring Boot", "PostgreSQL", "Redis", "Kafka", "REST APIs"), List.of(
                        new AnalyzerClient.Experience("Lumen Pay", "Senior Software Engineer", true, List.of(TOP_BULLET,
                                "Cut p99 latency of the payouts API by 40% by adding Redis caching",
                                "Moved reconciliation jobs to PostgreSQL partitioned tables",
                                "Mentored 3 engineers and ran weekly design reviews")),
                        new AnalyzerClient.Experience("Flipkart", "SDE II", false, List.of(
                                "Developed order-tracking REST APIs used by the mobile apps",
                                "Fixed a recurring outage in the order service by adding retries and monitoring")))));
    }
}
