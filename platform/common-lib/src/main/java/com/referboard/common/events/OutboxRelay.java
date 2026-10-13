package com.referboard.common.events;

import com.referboard.common.web.CorrelationId;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Moves committed outbox rows to Kafka. Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several
 * replicas of a service can run the relay concurrently without double-sending. On the first failure the batch
 * stops, preserving per-key ordering; the row is retried on the next tick. Delivery is at-least-once;
 * consumers de-duplicate on {@code metadata.eventId}.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${referboard.outbox.poll-interval-ms:1000}")
    public void relay() {
        Integer sent = tx.execute(status -> relayBatch());
        if (sent != null && sent > 0) {
            log.debug("Relayed {} outbox events", sent);
        }
    }

    int relayBatch() {
        List<Row> rows = jdbc.query("""
                        select id, topic, message_key, event_type, payload::text as payload,
                               payload->'metadata'->>'correlationId' as correlation_id
                        from outbox_events
                        where published_at is null
                        order by created_at
                        limit ?
                        for update skip locked
                        """,
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("message_key"),
                        rs.getString("event_type"), rs.getString("payload"), rs.getString("correlation_id")),
                batchSize);

        int sent = 0;
        for (Row row : rows) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
                record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
                if (row.correlationId() != null) {
                    record.headers().add(CorrelationId.HEADER, row.correlationId().getBytes(StandardCharsets.UTF_8));
                }
                kafka.send(record).get(10, TimeUnit.SECONDS);
                jdbc.update("update outbox_events set published_at = now(), attempts = attempts + 1 where id = ?",
                        row.id());
                sent++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Outbox publish failed for event {} ({}), will retry: {}", row.id(), row.eventType(),
                        e.getMessage());
                jdbc.update("update outbox_events set attempts = attempts + 1, last_error = ? where id = ?",
                        truncate(e.getMessage()), row.id());
                break;
            }
        }
        return sent;
    }

    @Scheduled(cron = "${referboard.outbox.cleanup-cron:0 30 3 * * *}")
    public void purgePublished() {
        int deleted = jdbc.update(
                "delete from outbox_events where published_at < now() - interval '7 days'");
        if (deleted > 0) {
            log.info("Purged {} published outbox events", deleted);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 1000));
    }

    private record Row(UUID id, String topic, String key, String eventType, String payload, String correlationId) {
    }
}
