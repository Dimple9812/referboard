package com.referboard.analyzer.repository;

import com.referboard.analyzer.domain.CoverLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoverLetterRepository extends JpaRepository<CoverLetter, UUID> {

    Optional<CoverLetter> findByIdAndUserId(UUID id, UUID userId);

    List<CoverLetter> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<CoverLetter> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    long countByUserId(UUID userId);

    /** AI letters since a point in time, for the daily cap (deleted ones still count: they cost a model call). */
    @Query(value = "select count(*) from cover_letters where user_id = :userId and origin = 'AI' and created_at >= :since",
            nativeQuery = true)
    long countAiSince(UUID userId, Instant since);
}
