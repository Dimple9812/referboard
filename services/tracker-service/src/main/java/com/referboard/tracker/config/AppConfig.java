package com.referboard.tracker.config;

import com.referboard.common.web.ApiException;
import feign.codec.ErrorDecoder;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** job-service errors: 404 is ours to return; anything else is a retryable 502. */
    @Bean
    public ErrorDecoder feignErrorDecoder() {
        return (methodKey, response) -> {
            if (response.status() == 404) {
                return ApiException.notFound("Job", methodKey);
            }
            if (response.status() == 401 || response.status() == 403) {
                return new ApiException(HttpStatus.valueOf(response.status()), "UPSTREAM_DENIED",
                        "Not allowed to read this job.");
            }
            log.warn("Upstream call {} failed with HTTP {}", methodKey, response.status());
            return new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
                    "A dependent service is unavailable. Please try again shortly.");
        };
    }

    /** Failing records are retried with backoff, then parked on {@code <topic>.DLT}; malformed JSON isn't retried. */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> {
                    log.error("Sending {}@{} offset {} to DLT: {}", record.topic(), record.partition(), record.offset(),
                            ex.getMessage());
                    return new TopicPartition(record.topic() + ".DLT", -1);
                });
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxElapsedTime(10_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    @Bean
    public OpenAPI trackerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ReferBoard Tracker API").version("v1")
                        .description("Your application board: stages, notes, dates and history."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
