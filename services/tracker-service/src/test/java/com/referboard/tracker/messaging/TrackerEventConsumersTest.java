package com.referboard.tracker.messaging;

import com.referboard.tracker.domain.Stage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrackerEventConsumersTest {

    @Test
    void mapsReferralStatusesToStages() {
        assertThat(TrackerEventConsumers.targetStage("REFERRAL_ASKED")).contains(Stage.REFERRAL_REQUESTED);
        assertThat(TrackerEventConsumers.targetStage("REFERRED")).contains(Stage.REFERRED);
        assertThat(TrackerEventConsumers.targetStage("CONNECTED")).isEmpty();
        assertThat(TrackerEventConsumers.targetStage("NO_RESPONSE")).isEmpty();
    }

    @Test
    void summarisesForTheTimeline() {
        assertThat(TrackerEventConsumers.summary("REFERRED", "Rohan Mehta")).isEqualTo("Referred by Rohan Mehta");
        assertThat(TrackerEventConsumers.summary("CONNECTED", null)).isEqualTo("Connected with a contact");
        assertThat(TrackerEventConsumers.summary("SOMETHING_NEW", "X")).isNull();
    }
}
