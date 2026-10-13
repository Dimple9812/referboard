package com.referboard.auth.config;

import com.referboard.events.Topics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicsConfigTest {

    @Test
    void userRegisteredIsCompactedAndKeptForever() {
        var topic = new KafkaTopicsConfig().userRegisteredTopic();
        assertThat(topic.name()).isEqualTo(Topics.USER_REGISTERED);
        assertThat(topic.configs()).containsEntry("cleanup.policy", "compact").containsEntry("retention.ms", "-1");
    }
}
