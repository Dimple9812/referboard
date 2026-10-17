package com.referboard.analyzer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The user's practice notes and confidence per prep question (upserted). */
@Repository
public class PrepAnswers {

    public record Answer(String itemId, String notes, String confidence, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public PrepAnswers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Answer> forPrep(UUID prepId) {
        return jdbc.query("select item_id, notes, confidence, updated_at from prep_answers where prep_id = ?",
                (rs, i) -> new Answer(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
                prepId);
    }

    public Answer save(UUID prepId, String itemId, String notes, String confidence, Instant now) {
        jdbc.update("""
                insert into prep_answers (prep_id, item_id, notes, confidence, updated_at) values (?, ?, ?, ?, ?)
                on conflict (prep_id, item_id) do update
                set notes = excluded.notes, confidence = excluded.confidence, updated_at = excluded.updated_at
                """, prepId, itemId, notes, confidence, Timestamp.from(now));
        return new Answer(itemId, notes, confidence, now);
    }
}
