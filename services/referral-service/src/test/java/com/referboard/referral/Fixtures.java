package com.referboard.referral;

import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.PersonType;
import com.referboard.referral.domain.UserSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Fixtures {

    public static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    public static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private Fixtures() {
    }

    public static JobSnapshot job() {
        JobSnapshot j = new JobSnapshot(JOB);
        j.setTitle("Senior Backend Engineer (Java)");
        j.setCompanyName("Northwind");
        j.setLocation("Bengaluru, India");
        j.setApplyUrl("https://boards.greenhouse.io/northwind/jobs/4012345");
        j.setSkills(List.of("Java", "Spring Boot", "Kafka", "Kubernetes"));
        j.setFetchedAt(Instant.now());
        return j;
    }

    public static UserSnapshot user() {
        UserSnapshot u = new UserSnapshot(USER);
        u.setFullName("Asha Rao");
        u.setCurrentTitle("Backend Engineer");
        u.setCurrentCompany("Lumen Pay");
        u.setExperienceYears(new BigDecimal("4.5"));
        u.setCollege("IIT Madras");
        u.setPastCompanies(List.of("Flipkart", "Zoho"));
        u.setSkills(List.of("Java", "Spring Boot", "Kafka", "PostgreSQL"));
        u.setAchievements(List.of("Cut p99 latency of the payments API by 40% by introducing Redis caching"));
        u.setFetchedAt(Instant.now());
        return u;
    }

    public static Contact contact(PersonType type, String name, String headline) {
        return new Contact(UUID.randomUUID(), USER, JOB, name, "https://www.linkedin.com/in/someone", headline, type);
    }
}
