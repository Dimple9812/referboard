package com.referboard.analyzer.suggest;

import com.referboard.skills.SkillTaxonomy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TruthGuardTest {

    final SkillTaxonomy taxonomy = SkillTaxonomy.defaultTaxonomy();
    final String before = "Cut p99 latency of the payouts API by 40% by adding Redis caching";

    @Test
    void allowsRephrasingWithTheSameFacts() {
        var v = TruthGuard.check(before, "Reduced payouts API p99 latency 40% through Redis caching", taxonomy, 300);
        assertThat(v.ok()).isTrue();
        assertThat(v.skillsIntroduced()).isEmpty();
    }

    @Test
    void rejectsInventedNumbers() {
        var v = TruthGuard.check(before, "Cut p99 latency by 40% for 12 services using Redis", taxonomy, 300);
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).isEqualTo("number not in source: 12");
        assertThat(TruthGuard.check("Built 2M tx/day pipeline", "Built a 2M tx/day pipeline, 3x faster", taxonomy, 300)
                .ok()).isFalse();
    }

    @Test
    void rejectsPlaceholdersAndNoOps() {
        assertThat(TruthGuard.check(before, "Cut latency by X% using Redis", taxonomy, 300).ok()).isFalse();
        assertThat(TruthGuard.check(before, "Improved [metric] with Redis", taxonomy, 300).ok()).isFalse();
        assertThat(TruthGuard.check(before, before, taxonomy, 300).reason()).isEqualTo("unchanged");
    }

    @Test
    void flagsSkillsTheOriginalDoesntMention() {
        var v = TruthGuard.check(before, "Cut p99 latency of the payouts API by 40% with Redis caching on Kubernetes",
                taxonomy, 300);
        assertThat(v.ok()).isTrue();
        assertThat(v.skillsIntroduced()).containsExactly("kubernetes");
    }
}
