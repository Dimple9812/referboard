package com.referboard.skills;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExperienceMathTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Test
    void sumsSequentialRolesInclusiveOfEndMonth() {
        var spans = List.of(
                ExperienceMath.span(LocalDate.of(2020, 7, 1), LocalDate.of(2022, 12, 1), false, TODAY), // 30 months
                ExperienceMath.span(LocalDate.of(2023, 1, 1), null, true, TODAY));                        // 46 months
        assertThat(ExperienceMath.totalYears(spans)).isEqualByComparingTo(new BigDecimal("6.3"));
    }

    @Test
    void overlappingRolesAreNotDoubleCounted() {
        var spans = List.of(
                ExperienceMath.span(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 1), false, TODAY),
                ExperienceMath.span(LocalDate.of(2021, 6, 1), LocalDate.of(2022, 5, 1), false, TODAY));
        assertThat(ExperienceMath.totalYears(spans)).isEqualByComparingTo(new BigDecimal("2.4"));
    }

    @Test
    void undatedRolesAreIgnoredAndNoDatesMeansUnknown() {
        assertThat(ExperienceMath.totalYears(List.of(ExperienceMath.span(null, null, false, TODAY)))).isNull();
    }

    @Test
    void parsesYearMonthAndRejectsFutureDates() {
        assertThat(ExperienceMath.parseYearMonth("2023-04", TODAY)).isEqualTo(LocalDate.of(2023, 4, 1));
        assertThat(ExperienceMath.parseYearMonth("2030-01", TODAY)).isNull();
        assertThat(ExperienceMath.parseYearMonth("garbage", TODAY)).isNull();
    }
}
