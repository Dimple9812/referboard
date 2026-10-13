package com.referboard.profile.parsing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured resume, as returned by the LLM (schema: {@code prompts/resume-schema.json}) or the heuristic parser.
 * Dates are {@code YYYY-MM}; anything the resume doesn't state is null/empty, never guessed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedResume(
        String fullName,
        String email,
        String phone,
        String location,
        String headline,
        String summary,
        List<String> skills,
        List<ParsedExperience> experiences,
        List<ParsedEducation> educations,
        List<String> certifications,
        List<String> links
) {
    public ParsedResume {
        skills = skills == null ? List.of() : skills;
        experiences = experiences == null ? List.of() : experiences;
        educations = educations == null ? List.of() : educations;
        certifications = certifications == null ? List.of() : certifications;
        links = links == null ? List.of() : links;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedExperience(
            String company,
            String title,
            String location,
            String startDate,
            String endDate,
            Boolean isCurrent,
            String description,
            List<String> bullets
    ) {
        public ParsedExperience {
            bullets = bullets == null ? List.of() : bullets;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedEducation(
            String institution,
            String degree,
            String fieldOfStudy,
            Integer startYear,
            Integer endYear,
            String grade
    ) {
    }
}
