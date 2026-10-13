package com.referboard.referral.service;

import com.referboard.referral.client.ProfileClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotServiceTest {

    @Test
    void achievementsAreVerbatimBulletsWithNumbers() {
        var p = new ProfileClient.Profile(true, "Asha", null, "Backend Engineer", "Lumen", null, null, List.of(),
                List.of(new ProfileClient.Experience("Lumen", "Backend Engineer", List.of(
                                "Built the ledger service", "Cut p99 latency by 40%", "Led a team of 3 engineers")),
                        new ProfileClient.Experience("Flipkart", "SDE", List.of("Shipped 12 features"))));
        assertThat(SnapshotService.achievements(p))
                .containsExactly("Cut p99 latency by 40%", "Led a team of 3 engineers", "Shipped 12 features");
        assertThat(SnapshotService.pastCompanies(p)).containsExactly("Flipkart");
    }

    @Test
    void excerptStripsHtmlAndCutsAtAWord() {
        String html = "<p>We are <b>hiring</b>.</p>" + "<p>" + "word ".repeat(300) + "</p>";
        String ex = SnapshotService.excerpt(html);
        assertThat(ex).startsWith("We are hiring. word").endsWith("…").doesNotContain("<");
        assertThat(ex.length()).isLessThanOrEqualTo(SnapshotService.EXCERPT_CHARS + 1);
    }
}
