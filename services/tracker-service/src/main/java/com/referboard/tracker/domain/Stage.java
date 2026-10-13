package com.referboard.tracker.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Board columns, left to right. The user may drag a card to any column (a Kanban board shouldn't argue), but
 * automatic moves from referral events only ever advance a card from an early stage, never out of
 * INTERVIEW/OFFER/REJECTED.
 */
public enum Stage {
    SAVED("Saved"),
    APPLIED("Applied"),
    REFERRAL_REQUESTED("Referral requested"),
    REFERRED("Referred"),
    INTERVIEW("Interview"),
    OFFER("Offer"),
    REJECTED("Rejected");

    private static final Set<Stage> AUTO_ADVANCE_FROM = EnumSet.of(SAVED, APPLIED, REFERRAL_REQUESTED);

    private final String label;

    Stage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True if an automatic (event-driven) move from this stage to {@code target} is an advance. */
    public boolean canAutoAdvanceTo(Stage target) {
        return AUTO_ADVANCE_FROM.contains(this) && target.ordinal() > ordinal() && target.ordinal() <= REFERRED.ordinal();
    }
}
