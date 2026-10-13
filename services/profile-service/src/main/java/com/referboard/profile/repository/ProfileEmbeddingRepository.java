package com.referboard.profile.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** pgvector column access via native SQL (the vector column is intentionally not mapped in the JPA entity). */
@Repository
public class ProfileEmbeddingRepository {

    private final JdbcTemplate jdbc;

    public ProfileEmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the embedding only if the profile is still at {@code profileVersion}; returns false when a newer edit
     * has landed meanwhile (that edit's own sync will store a fresher embedding).
     */
    public boolean storeIfCurrent(UUID userId, float[] embedding, String model, long profileVersion) {
        int rows = jdbc.update("""
                        update profiles
                           set embedding = cast(? as public.vector), embedding_model = ?, embedding_version = ?
                         where user_id = ? and profile_version = ?
                        """,
                embedding == null ? null : toLiteral(embedding), embedding == null ? null : model, profileVersion,
                userId, profileVersion);
        return rows == 1;
    }

    public static String toLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
