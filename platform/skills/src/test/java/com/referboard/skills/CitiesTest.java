package com.referboard.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitiesTest {

    @Test
    void canonicalisesAliases() {
        assertThat(Cities.canonical("Bangalore")).isEqualTo("bengaluru");
        assertThat(Cities.canonical("Gurgaon")).isEqualTo("gurugram");
        assertThat(Cities.canonical("Lisbon")).isEqualTo("lisbon");
    }

    @Test
    void spellingsCoverEveryAlias() {
        assertThat(Cities.spellings("bangalore")).containsExactlyInAnyOrder("bengaluru", "bangalore", "blr");
    }

    @Test
    void mentionsMatchesWholeWordsUnderAnySpelling() {
        assertThat(Cities.mentions("Bengaluru, Karnataka, India", "Bangalore")).isTrue();
        assertThat(Cities.mentions("Gurgaon (Hybrid)", "gurugram")).isTrue();
        assertThat(Cities.mentions("Punerum", "Pune")).isFalse();
    }
}
