package com.referboard.profile.service;

import com.referboard.profile.domain.ProfileSkill;
import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillAssemblerTest {

    private final SkillAssembler assembler = new SkillAssembler(SkillTaxonomy.defaultTaxonomy());

    @Test
    void normalisesDeduplicatesAndTracksEvidence() {
        var skills = assembler.assemble(List.of(
                new SkillAssembler.Candidate("K8s", null, ProfileSkill.Source.PARSED),
                new SkillAssembler.Candidate("Kubernetes", new BigDecimal("2"), ProfileSkill.Source.USER),
                new SkillAssembler.Candidate("Java", null, ProfileSkill.Source.PARSED),
                new SkillAssembler.Candidate("Terraform", null, ProfileSkill.Source.PARSED),
                new SkillAssembler.Candidate("Internal DSL", null, ProfileSkill.Source.USER)),
                "Deployed services on Kubernetes. Wrote an internal DSL for pricing rules.");

        assertThat(skills).extracting(ProfileSkill::getName)
                .containsExactly("kubernetes", "java", "terraform", "internal dsl");

        ProfileSkill k8s = skills.getFirst();
        assertThat(k8s.getDisplayName()).isEqualTo("Kubernetes");
        assertThat(k8s.getSource()).as("USER wins over PARSED").isEqualTo(ProfileSkill.Source.USER);
        assertThat(k8s.getYears()).isEqualByComparingTo("2");
        assertThat(k8s.getEvidence()).isEqualTo(ProfileSkill.Evidence.BULLET);

        assertThat(skills.get(1).getEvidence()).as("listed but never used in a bullet")
                .isEqualTo(ProfileSkill.Evidence.SKILLS_LIST);
        assertThat(skills.get(3).getEvidence()).as("unknown skills matched literally")
                .isEqualTo(ProfileSkill.Evidence.BULLET);
        assertThat(skills.get(3).getDisplayName()).isEqualTo("Internal DSL");
    }
}
