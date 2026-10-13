package com.referboard.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A job description reduced to what scoring needs. Skills are canonical taxonomy names (unknown skills are kept as
 * lower-case strings). Produced by the LLM parser (strict schema) or the heuristic parser.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedJd(
        String title,
        String company,
        Seniority seniority,
        Integer minYears,
        Integer maxYears,
        List<String> requiredSkills,
        List<String> preferredSkills,
        List<String> responsibilities,
        List<String> keywords,
        EducationRequirement education,
        String location,
        Boolean remote
) {
    public ParsedJd {
        requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
        preferredSkills = preferredSkills == null ? List.of() : List.copyOf(preferredSkills);
        responsibilities = responsibilities == null ? List.of() : List.copyOf(responsibilities);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /** @param level minimum degree asked for; NONE when not mentioned */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EducationRequirement(DegreeLevel level, List<String> fields, boolean mandatory) {
        public EducationRequirement {
            fields = fields == null ? List.of() : List.copyOf(fields);
            level = level == null ? DegreeLevel.NONE : level;
        }
    }

    public enum DegreeLevel {
        NONE, DIPLOMA, BACHELORS, MASTERS, PHD
    }

    /** Career levels with a rank for comparison; LEAD, STAFF and MANAGER share a rank on different tracks. */
    public enum Seniority {
        INTERN(0), JUNIOR(1), MID(2), SENIOR(3), LEAD(4), STAFF(4), MANAGER(4), PRINCIPAL(5), DIRECTOR(6);

        private final int rank;

        Seniority(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }
}
