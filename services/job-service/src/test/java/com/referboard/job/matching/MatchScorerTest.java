package com.referboard.job.matching;

import com.referboard.job.matching.MatchScorer.JobFacts;
import com.referboard.job.matching.MatchScorer.Preferences;
import com.referboard.job.matching.MatchScorer.UserFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MatchScorerTest {

    private static final UserFacts BACKEND_DEV = new UserFacts(
            List.of("java", "spring boot", "kafka", "postgresql", "docker", "aws"),
            new BigDecimal("4.5"),
            new Preferences(List.of("Backend Engineer"), List.of("Bangalore"), false, true, 2_500_000L, null));

    private static JobFacts job(String title, String location, boolean remote, String seniority, List<String> skills,
                                Double cos, Long salaryMax) {
        return new JobFacts(title, location, remote, seniority, salaryMax, salaryMax == null ? null : "INR", skills, cos);
    }

    @Test
    void strongFitScoresHighAndExplainsItself() {
        var b = MatchScorer.score(BACKEND_DEV, job("Senior Backend Engineer", "Bengaluru, Karnataka", false, "SENIOR",
                List.of("java", "spring boot", "kafka", "postgresql", "kubernetes"), 0.62, 4_000_000L));

        assertThat(b.matchPercent()).isBetween(80, 95);
        assertThat(b.matchedSkills()).containsExactly("java", "spring boot", "kafka", "postgresql");
        assertThat(b.missingSkills()).containsExactly("kubernetes");
        assertThat(b.skills()).isEqualTo(0.8);
        assertThat(b.preferences()).as("role 1, location 1 (Bangalore = Bengaluru), seniority MID vs SENIOR 0.6, salary 1")
                .isCloseTo(0.9, within(0.001));
        assertThat(b.reasons()).contains("You have 4 of the 5 skills this job asks for", "In a location you prefer");
        assertThat(b.weights()).containsEntry("semantic", 0.45).containsEntry("skills", 0.35).containsEntry("preferences", 0.2);
    }

    @Test
    void poorFitScoresLow() {
        var b = MatchScorer.score(BACKEND_DEV, job("iOS Engineer", "Mumbai", false, "PRINCIPAL",
                List.of("swift", "swiftui", "ios"), 0.12, 1_500_000L));

        assertThat(b.matchPercent()).isLessThan(20);
        assertThat(b.matchedSkills()).isEmpty();
        assertThat(b.reasons()).contains("Outside your preferred locations", "Listed pay tops out below your minimum")
                .anyMatch(r -> r.startsWith("Seniority gap"));
    }

    @Test
    void missingSignalsAreDroppedAndWeightsRenormalised() {
        var noVectorNoSkills = MatchScorer.score(
                new UserFacts(List.of("java"), null, Preferences.NONE),
                job("Backend Engineer", "Pune", false, null, List.of(), null, null));
        assertThat(noVectorNoSkills.matchPercent()).as("nothing to compare").isZero();
        assertThat(noVectorNoSkills.weights()).isEmpty();

        var skillsOnly = MatchScorer.score(new UserFacts(List.of("java", "kafka"), null, Preferences.NONE),
                job("Backend Engineer", "Pune", false, null, List.of("java", "kafka"), null, null));
        assertThat(skillsOnly.matchPercent()).as("only the skills component applies, and it's perfect").isEqualTo(100);
        assertThat(skillsOnly.weights()).containsExactly(java.util.Map.entry("skills", 1.0));
    }

    @Test
    void remotePreferences() {
        var remoteOnlyUser = new UserFacts(List.of(), null,
                new Preferences(List.of(), List.of(), true, true, null, null));
        assertThat(MatchScorer.score(remoteOnlyUser, job("Dev", "Remote", true, null, List.of(), null, null))
                .preferences()).isEqualTo(1.0);
        assertThat(MatchScorer.score(remoteOnlyUser, job("Dev", "Pune", false, null, List.of(), null, null))
                .preferences()).isEqualTo(0.0);
    }

    @Test
    void helpers() {
        assertThat(MatchScorer.rescaleCosine(0.15)).isZero();
        assertThat(MatchScorer.rescaleCosine(0.70)).isCloseTo(1.0, within(1e-9));
        assertThat(MatchScorer.roleOverlap("Backend Engineer", "Senior Backend Engineer (Payments)")).isEqualTo(1.0);
        assertThat(MatchScorer.roleOverlap("Data Engineer", "Backend Engineer")).isZero();
        assertThat(MatchScorer.levelFromYears(new BigDecimal("1"))).isEqualTo("ENTRY");
        assertThat(MatchScorer.levelFromYears(new BigDecimal("6.3"))).isEqualTo("SENIOR");
    }
}
