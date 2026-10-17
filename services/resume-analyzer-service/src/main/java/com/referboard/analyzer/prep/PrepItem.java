package com.referboard.analyzer.prep;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One thing to prepare. Questions come from the curated bank, the posting, or the user's own resume lines; the
 * planner never writes answers, only what a good answer covers and (from the resume) the user's own example.
 */
public record PrepItem(
        String id,
        Section section,
        String question,
        @Schema(description = "What the interviewer is checking") String why,
        @Schema(description = "Points a strong answer covers") List<String> pointers,
        @Schema(description = "The user's own resume line this draws on, if any") Example example,
        @Schema(description = "Display name of the skill, for technical and gap questions") String skill,
        Source source) {

    public enum Section { EXPERIENCE, TECHNICAL, ROLE, BEHAVIOURAL, GAPS, ASK }

    /** BANK: curated; RESUME: a line from the resume; POSTING: from the job description; AI: model-suggested. */
    public enum Source { BANK, RESUME, POSTING, AI }

    public record Example(String company, String title, String text) {
    }

    public PrepItem {
        pointers = pointers == null ? List.of() : List.copyOf(pointers);
    }
}
