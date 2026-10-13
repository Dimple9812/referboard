package com.referboard.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.events.CorrelationIdRecordInterceptor;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.events.OutboxEventPublisher;
import com.referboard.common.events.OutboxRelay;
import com.referboard.common.events.ProcessedEventStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Event plumbing for services that use Kafka + JDBC: outbox publisher, outbox relay, idempotent-consumer
 * store and the correlation-id record interceptor. Disable the relay with
 * {@code referboard.outbox.relay-enabled=false} (e.g. in slice tests).
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@ConditionalOnClass({KafkaTemplate.class, JdbcTemplate.class})
public class CommonEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventJson eventJson(ObjectMapper mapper, @Value("${spring.application.name:unknown}") String appName) {
        return new EventJson(mapper, appName);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public EventPublisher eventPublisher(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new OutboxEventPublisher(jdbc, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public ProcessedEventStore processedEventStore(JdbcTemplate jdbc) {
        return new ProcessedEventStore(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdRecordInterceptor correlationIdRecordInterceptor() {
        return new CorrelationIdRecordInterceptor();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "referboard.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean({JdbcTemplate.class, KafkaTemplate.class, PlatformTransactionManager.class})
    static class RelayConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager txManager,
                                @Value("${referboard.outbox.batch-size:100}") int batchSize) {
            return new OutboxRelay(jdbc, kafka, new TransactionTemplate(txManager), batchSize);
        }
    }
}
