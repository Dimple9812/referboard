package com.referboard.analyzer.suggest;

import java.util.List;

/**
 * One proposed improvement. Applicable suggestions carry a before → after edit at {@link Target}; advice-only ones
 * (gap, seniority, evidence) don't change the resume.
 *
 * @param skillsIntroduced     skills the edit would claim that the resume doesn't show at that place
 * @param requiresConfirmation true when the edit adds a claim; the user must confirm it's true before it's applied
 * @param impact               projected score change if accepted on its own (null for advice)
 */
public record Suggestion(
        String id,
        Type type,
        Priority priority,
        String title,
        String rationale,
        String before,
        String after,
        Target target,
        List<String> skillsIntroduced,
        boolean requiresConfirmation,
        String confirmationLabel,
        boolean applicable,
        Integer impact,
        Origin origin
) {
    public static final String CONFIRM_LABEL = "Add only if you have this experience";

    public enum Type {
        SKILLS_REORDER, SKILL_WORDING, ADD_SKILL, BULLET_REWRITE, SUMMARY_REWRITE, EVIDENCE_ADVICE, GAP_ADVICE,
        SENIORITY_ADVICE
    }

    public enum Priority { HIGH, MEDIUM, LOW }

    public enum Origin { RULES, AI }

    /** Where an edit applies: a bullet, the skills list (new order, or one skill), or the summary. */
    public record Target(Integer experienceIndex, Integer bulletIndex, String skill, List<String> skillsOrder) {
        public static Target none() {
            return new Target(null, null, null, null);
        }
    }

    public Suggestion withImpact(Integer newImpact) {
        return new Suggestion(id, type, priority, title, rationale, before, after, target, skillsIntroduced,
                requiresConfirmation, confirmationLabel, applicable, newImpact, origin);
    }

    public Suggestion withPriority(Priority p) {
        return new Suggestion(id, type, p, title, rationale, before, after, target, skillsIntroduced,
                requiresConfirmation, confirmationLabel, applicable, impact, origin);
    }
}
