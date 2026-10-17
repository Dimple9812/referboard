package com.referboard.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Failing records are retried with backoff, then parked on {@code <topic>.DLT}. */
    @Bean
    public org.springframework.kafka.listener.DefaultErrorHandler kafkaErrorHandler(
            org.springframework.kafka.core.KafkaTemplate<String, String> kafka) {
        var recoverer = new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", -1));
        var backOff = new org.springframework.util.backoff.ExponentialBackOff(500, 2.0);
        backOff.setMaxElapsedTime(10_000);
        var handler = new org.springframework.kafka.listener.DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
