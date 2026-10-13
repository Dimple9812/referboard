package com.referboard.analyzer.model;

import com.referboard.analyzer.model.ParsedJd.DegreeLevel;
import com.referboard.analyzer.model.ParsedJd.Seniority;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LevelsTest {

    @Test
    void titles() {
        assertThat(Levels.fromTitle("Senior Staff Engineer")).isEqualTo(Seniority.STAFF);
        assertThat(Levels.fromTitle("SDE II")).isEqualTo(Seniority.MID);
        assertThat(Levels.fromTitle("SDE-1")).isEqualTo(Seniority.JUNIOR);
        assertThat(Levels.fromTitle("Engineering Manager, Payments")).isEqualTo(Seniority.MANAGER);
        assertThat(Levels.fromTitle("Product Manager")).as("not an EM").isNull();
        assertThat(Levels.fromTitle("Backend Engineer")).isNull();
        assertThat(Levels.ofCandidate("Backend Engineer", new BigDecimal("6"))).isEqualTo(Seniority.SENIOR);
    }

    @Test
    void degrees() {
        assertThat(Levels.degreeLevel("B.Tech")).isEqualTo(DegreeLevel.BACHELORS);
        assertThat(Levels.degreeLevel("M.Tech (CSE)")).isEqualTo(DegreeLevel.MASTERS);
        assertThat(Levels.degreeLevel("MCA")).isEqualTo(DegreeLevel.MASTERS);
        assertThat(Levels.degreeLevel("Ph.D.")).isEqualTo(DegreeLevel.PHD);
        assertThat(Levels.degreeLevel("Diploma in Electronics")).isEqualTo(DegreeLevel.DIPLOMA);
    }
}
