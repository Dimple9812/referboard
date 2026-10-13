package com.referboard.tracker.domain;

import org.junit.jupiter.api.Test;

import static com.referboard.tracker.domain.Stage.*;
import static org.assertj.core.api.Assertions.assertThat;

class StageTest {

    @Test
    void referralEventsOnlyAdvanceEarlyCards() {
        assertThat(SAVED.canAutoAdvanceTo(REFERRAL_REQUESTED)).isTrue();
        assertThat(APPLIED.canAutoAdvanceTo(REFERRED)).isTrue();
        assertThat(REFERRAL_REQUESTED.canAutoAdvanceTo(REFERRED)).isTrue();
        assertThat(REFERRED.canAutoAdvanceTo(REFERRAL_REQUESTED)).as("never backwards").isFalse();
        assertThat(INTERVIEW.canAutoAdvanceTo(REFERRED)).isFalse();
        assertThat(REJECTED.canAutoAdvanceTo(REFERRED)).as("a rejection isn't undone by a referral").isFalse();
        assertThat(SAVED.canAutoAdvanceTo(INTERVIEW)).as("only referral stages are automatic").isFalse();
    }

    @Test
    void firstMoveToAppliedRecordsTheDate() throws Exception {
        var ctor = Application.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Application a = ctor.newInstance();
        var t1 = java.time.Instant.parse("2026-10-01T10:00:00Z");
        var t2 = java.time.Instant.parse("2026-10-05T10:00:00Z");
        a.moveTo(APPLIED, t1);
        a.moveTo(INTERVIEW, t2);
        a.moveTo(APPLIED, t2);
        assertThat(a.getAppliedAt()).isEqualTo(t1);
        assertThat(a.getStageChangedAt()).isEqualTo(t2);
    }
}
