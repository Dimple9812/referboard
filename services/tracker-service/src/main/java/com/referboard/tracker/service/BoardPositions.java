package com.referboard.tracker.service;

import java.util.List;

/**
 * Fractional ordering within a column: a moved card takes the midpoint between its new neighbours, so a drag
 * updates one row. When neighbours get too close (many drags into the same gap) the column is renumbered.
 */
public final class BoardPositions {

    public static final double STEP = 1024;
    static final double MIN_GAP = 1e-6;

    private BoardPositions() {
    }

    /**
     * Position for a card placed at {@code index} among {@code others} (the column's other cards' positions,
     * ascending). Returns NaN when there is no room and the column must be renumbered first.
     */
    public static double at(List<Double> others, int index) {
        if (others.isEmpty()) {
            return 0;
        }
        int i = Math.max(0, Math.min(index, others.size()));
        if (i == 0) {
            return others.getFirst() - STEP;
        }
        if (i == others.size()) {
            return others.getLast() + STEP;
        }
        double prev = others.get(i - 1);
        double next = others.get(i);
        if (next - prev < MIN_GAP) {
            return Double.NaN;
        }
        return (prev + next) / 2;
    }

    /** Evenly spaced positions for {@code n} cards. */
    public static double renumbered(int i) {
        return i * STEP;
    }
}
