package com.referboard.analyzer.repository;

import com.referboard.analyzer.domain.InterviewPrep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewPrepRepository extends JpaRepository<InterviewPrep, UUID> {

    Optional<InterviewPrep> findByIdAndUserId(UUID id, UUID userId);

    Optional<InterviewPrep> findByAnalysisIdAndUserId(UUID analysisId, UUID userId);

    List<InterviewPrep> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<InterviewPrep> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    long countByUserId(UUID userId);
}
