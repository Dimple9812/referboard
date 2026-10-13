package com.referboard.analyzer.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Per-user monthly analysis counter, incremented atomically against the limit (no read-then-write race). */
@Repository
public class UsageCounters {

    private final JdbcTemplate jdbc;

    public UsageCounters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes one unit if under {@code limit}; returns the new count, or -1 if the limit is reached. */
    public int tryIncrement(UUID userId, String period, int limit) {
        return jdbc.query("""
                        insert into usage_counters (user_id, period, analyses) values (?, ?, 1)
                        on conflict (user_id, period) do update set analyses = usage_counters.analyses + 1
                        where usage_counters.analyses < ?
                        returning analyses
                        """, (rs, i) -> rs.getInt(1), userId, period, limit)
                .stream().findFirst().orElse(-1);
    }

    /** Unlimited users are still counted (for analytics); no limit check. */
    public void increment(UUID userId, String period) {
        jdbc.update("""
                insert into usage_counters (user_id, period, analyses) values (?, ?, 1)
                on conflict (user_id, period) do update set analyses = usage_counters.analyses + 1
                """, userId, period);
    }

    /** Gives a unit back when an analysis fails after it was counted. */
    public void decrement(UUID userId, String period) {
        jdbc.update("update usage_counters set analyses = greatest(analyses - 1, 0) where user_id = ? and period = ?",
                userId, period);
    }

    public int used(UUID userId, String period) {
        return jdbc.query("select analyses from usage_counters where user_id = ? and period = ?",
                (rs, i) -> rs.getInt(1), userId, period).stream().findFirst().orElse(0);
    }
}
