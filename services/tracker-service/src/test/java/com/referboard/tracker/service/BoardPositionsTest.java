package com.referboard.tracker.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoardPositionsTest {

    @Test
    void placesAtEndsAndBetweenNeighbours() {
        List<Double> col = List.of(0.0, 1024.0, 2048.0);
        assertThat(BoardPositions.at(List.of(), 3)).isZero();
        assertThat(BoardPositions.at(col, 0)).isEqualTo(-1024.0);
        assertThat(BoardPositions.at(col, 1)).isEqualTo(512.0);
        assertThat(BoardPositions.at(col, 3)).isEqualTo(3072.0);
        assertThat(BoardPositions.at(col, 99)).as("clamped to the end").isEqualTo(3072.0);
    }

    @Test
    void signalsRenumberingWhenTheGapIsExhausted() {
        assertThat(BoardPositions.at(List.of(1.0, 1.0 + 1e-9), 1)).isNaN();
    }

    @Test
    void repeatedDropsIntoTheSameGapEventuallyNeedRenumbering() {
        double lo = 0;
        double hi = 1024;
        int drops = 0;
        while (!Double.isNaN(BoardPositions.at(List.of(lo, hi), 1))) {
            hi = BoardPositions.at(List.of(lo, hi), 1);
            drops++;
        }
        assertThat(drops).isGreaterThan(25);   // plenty of headroom before a renumber is needed
    }
}
