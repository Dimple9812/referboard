package com.referboard.referral.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

/** job-service, called with the user's forwarded token. */
@FeignClient(name = "job-service", url = "${referboard.clients.job-service-url:}")
public interface JobClient {

    @GetMapping("/api/jobs/{id}")
    JobDetail job(@PathVariable("id") UUID id);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JobDetail(Job job, String descriptionHtml, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Job(UUID id, String title, String companyName, String location, String applyUrl, List<Skill> skills) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Skill(String name, String label) {
    }
}
