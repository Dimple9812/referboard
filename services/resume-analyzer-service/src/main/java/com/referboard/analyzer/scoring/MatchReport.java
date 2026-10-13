package com.referboard.analyzer.scoring;

import java.util.List;

/** The deterministic part of an analysis: score, breakdown, skills and ATS checks. */
public record MatchReport(
        int score,
        Verdict verdict,
        List<Component> breakdown,
        List<SkillMatch> matchedSkills,
        MissingSkills missingSkills,
        List<AtsCheck> atsChecks,
        double keywordCoverage,
        List<String> missingKeywords
) {

    public enum Verdict { STRONG, GOOD, PARTIAL, WEAK }

    /**
     * @param weight  nominal weight (sums to 100 over all components)
     * @param score   0..1, or null when the JD gives nothing to compare (the weight is then redistributed)
     * @param points  contribution to the final score after redistribution
     */
    public record Component(String key, String label, int weight, Double score, double points, String detail) {
    }

    public record SkillMatch(String name, String label, boolean required, SkillEvidence.Level evidence,
                             String usedIn) {
    }

    public record SkillRef(String name, String label) {
    }

    public record MissingSkills(List<SkillRef> required, List<SkillRef> preferred, List<SkillRef> weaklyEvidenced) {
    }

    public enum CheckStatus { PASS, WARN, FAIL }

    public record AtsCheck(String id, String label, CheckStatus status, String detail) {
    }

    public static Verdict verdictFor(int score) {
        if (score >= 80) {
            return Verdict.STRONG;
        }
        if (score >= 65) {
            return Verdict.GOOD;
        }
        if (score >= 45) {
            return Verdict.PARTIAL;
        }
        return Verdict.WEAK;
    }
}
