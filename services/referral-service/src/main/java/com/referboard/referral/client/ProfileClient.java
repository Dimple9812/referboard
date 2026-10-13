package com.referboard.referral.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.List;

/** profile-service, called with the user's forwarded token (so it can only ever return that user's profile). */
@FeignClient(name = "profile-service", url = "${referboard.clients.profile-service-url:}")
public interface ProfileClient {

    @GetMapping("/api/profile")
    Profile me();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(boolean exists, String fullName, String headline, String currentTitle, String currentCompany,
                   BigDecimal totalExperienceYears, String college, List<Skill> skills,
                   List<Experience> experiences) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Skill(String name, String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Experience(String company, String title, List<String> bullets) {
    }
}
