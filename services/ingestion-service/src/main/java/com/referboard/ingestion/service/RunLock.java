package com.referboard.ingestion.service;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Cluster-wide "only one ingestion run at a time" using a PostgreSQL session advisory lock. The lock lives as long
 * as the connection, so a crashed instance releases it automatically.
 */
@Component
public class RunLock {

    private static final long LOCK_KEY = 0x5265666572426FL; // "ReferBo"

    private final DataSource dataSource;

    public RunLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    public Optional<Held> tryAcquire() {
        try {
            Connection connection = dataSource.getConnection();
            try (PreparedStatement ps = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        connection.close();
                        return Optional.empty();
                    }
                }
            }
            return Optional.of(() -> {
                try (connection; PreparedStatement ps = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    ps.setLong(1, LOCK_KEY);
                    ps.execute();
                } catch (SQLException ignored) {
                    // closing the connection releases the lock anyway
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot acquire ingestion lock", e);
        }
    }
}
