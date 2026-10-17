package com.referboard.billing.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The end of each user's Pro access. Updated under a row lock so two payments can't both start from the same date. */
@Repository
public class Entitlements {

    private final JdbcTemplate jdbc;

    public Entitlements(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Instant> proUntil(UUID userId) {
        return jdbc.query("select pro_until from entitlements where user_id = ?",
                (rs, i) -> rs.getTimestamp(1).toInstant(), userId).stream().findFirst();
    }

    /** Locks (creating if needed) the user's row and returns its current end, or null for none. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant lock(UUID userId) {
        jdbc.update("insert into entitlements (user_id, pro_until) values (?, 'epoch') on conflict do nothing", userId);
        Timestamp t = jdbc.queryForObject("select pro_until from entitlements where user_id = ? for update",
                Timestamp.class, userId);
        Instant until = t == null ? null : t.toInstant();
        return until == null || until.equals(Instant.EPOCH) ? null : until;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void set(UUID userId, Instant proUntil) {
        jdbc.update("update entitlements set pro_until = ?, updated_at = now() where user_id = ?",
                Timestamp.from(proUntil), userId);
    }
}
