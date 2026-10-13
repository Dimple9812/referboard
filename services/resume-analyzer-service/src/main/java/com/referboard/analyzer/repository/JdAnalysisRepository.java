package com.referboard.analyzer.repository;

import com.referboard.analyzer.domain.JdAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface JdAnalysisRepository extends JpaRepository<JdAnalysis, UUID> {

    Optional<JdAnalysis> findByIdAndUserId(UUID id, UUID userId);

    Page<JdAnalysis> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<JdAnalysis> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId, Pageable pageable);

    Optional<JdAnalysis> findFirstByUserIdAndJdHashAndResumeRefIdAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID userId, String jdHash, UUID resumeRefId, Instant after);
}
