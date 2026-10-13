package com.referboard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractsTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void profileUpdatedRoundTripsAndIsKeyedByUser() throws Exception {
        UUID userId = UUID.randomUUID();
        var event = new ProfileUpdatedEvent(
                EventMetadata.of("ProfileUpdated", "profile-service", "corr-1"),
                userId, 3, "Backend engineer", List.of("java", "spring boot"), new BigDecimal("4.5"),
                "IIT Delhi", List.of("Flipkart"),
                new ProfileUpdatedEvent.Preferences(List.of("Backend Engineer"), List.of("Bengaluru"), false, true,
                        2_000_000L, "MID"),
                new float[]{0.1f, 0.2f}, "text-embedding-3-small", 2);

        String json = mapper.writeValueAsString(event);
        ProfileUpdatedEvent back = mapper.readValue(json, ProfileUpdatedEvent.class);

        assertThat(back.userId()).isEqualTo(userId);
        assertThat(back.embedding()).containsExactly(0.1f, 0.2f);
        assertThat(back.preferences().minSalaryInr()).isEqualTo(2_000_000L);
        assertThat(back.key()).isEqualTo(userId.toString());
        assertThat(back.topic()).isEqualTo(Topics.PROFILE_UPDATED);
        // derived accessors must not leak into the wire format
        assertThat(json).doesNotContain("\"topic\"").doesNotContain("\"key\"");
    }

    @Test
    void consumersTolerateUnknownFields() throws Exception {
        String json = """
                {"metadata":{"eventId":"%s","eventType":"JobClosed","schemaVersion":1,
                 "occurredAt":"2026-10-13T00:00:00Z","producer":"ingestion-service","correlationId":null},
                 "jobId":"%s","closedAt":"2026-10-13T00:00:00Z","reason":"UNSEEN_7_DAYS",
                 "fieldAddedInFutureVersion":"ignored"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        JobClosedEvent event = mapper.readValue(json, JobClosedEvent.class);

        assertThat(event.reason()).isEqualTo(JobClosedEvent.REASON_UNSEEN_7_DAYS);
        assertThat(event.closedAt()).isEqualTo(Instant.parse("2026-10-13T00:00:00Z"));
    }
}
