package com.referboard.auth.config;

import com.referboard.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * {@code user.registered.v1} is a compacted topic with no time-based retention: Kafka keeps the latest event per
 * user (the key) indefinitely, so a service deployed later (e.g. notification-service learning email addresses)
 * can rebuild its copy by reading from the beginning. Partition and replica counts are left to the broker defaults.
 * Created on startup if missing; an existing topic's settings are not changed.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicsConfig {

    @Bean
    public NewTopic userRegisteredTopic() {
        return TopicBuilder.name(Topics.USER_REGISTERED)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .build();
    }
}
