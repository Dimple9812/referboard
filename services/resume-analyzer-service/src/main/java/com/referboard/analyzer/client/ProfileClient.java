package com.referboard.analyzer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** profile-service, called with the user's forwarded token (it only ever returns that user's resumes). */
@FeignClient(name = "profile-service", url = "${referboard.clients.profile-service-url:}")
public interface ProfileClient {

    @GetMapping("/api/profile/resumes")
    List<ResumeInfo> resumes();

    @GetMapping("/api/profile/resumes/{id}/parsed")
    ParsedResumeResponse parsed(@PathVariable("id") UUID id);

    @GetMapping("/api/profile/resumes/{id}/file")
    byte[] file(@PathVariable("id") UUID id);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResumeInfo(UUID id, String fileName, String contentType, long sizeBytes, String parseStatus,
                      boolean primary, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParsedResumeResponse(UUID resumeId, String parser, ParsedResume parsed, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParsedResume(String fullName, String email, String phone, String location, String headline,
                        String summary, List<String> skills, List<ParsedExperience> experiences,
                        List<ParsedEducation> educations, List<String> certifications, List<String> links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParsedExperience(String company, String title, String location, String startDate, String endDate,
                            Boolean isCurrent, String description, List<String> bullets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParsedEducation(String institution, String degree, String fieldOfStudy, Integer startYear,
                           Integer endYear, String grade) {
    }
}
