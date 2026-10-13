package com.referboard.analyzer;

import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.model.ResumeContent.Education;
import com.referboard.analyzer.model.ResumeContent.Experience;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Fixtures {

    private Fixtures() {
    }

    public static String backendJd() {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/backend-jd.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Matches most of the backend JD: Java/Spring Boot/PostgreSQL used in roles, Kafka only listed (weak),
     * Kubernetes written as "K8s", gRPC/Go/AWS/Terraform absent. 7+ years, B.Tech CS.
     */
    public static ResumeContent resume() {
        return new ResumeContent("Asha Rao", "asha.rao@example.com", "+91 98765 43210", "Bengaluru",
                "Backend engineer, payments", null,
                List.of("Java", "Spring Boot", "PostgreSQL", "Redis", "Docker", "K8s", "Kafka", "Python"),
                List.of(
                        new Experience("Lumen Pay", "Senior Software Engineer", "Bengaluru", "2021-03", null, true,
                                List.of("Built Spring Boot services in Java for UPI settlements processing 2M "
                                                + "transactions a day",
                                        "Cut p99 latency of the payouts API by 40% by adding Redis caching",
                                        "Moved reconciliation jobs to PostgreSQL partitioned tables",
                                        "Mentored 3 engineers and ran weekly design reviews")),
                        new Experience("Flipkart", "SDE II", "Bengaluru", "2018-07", "2021-02", false,
                                List.of("Developed order-tracking APIs used by the mobile apps",
                                        "Containerised services with Docker and wrote integration tests"))),
                List.of(new Education("IIT Madras", "B.Tech", "Computer Science", 2014, 2018, null)),
                List.of(), List.of());
    }
}
