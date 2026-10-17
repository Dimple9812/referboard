package com.referboard.interview.repository;

import com.referboard.interview.domain.PrepPack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrepPackRepository extends JpaRepository<PrepPack, UUID> {

    Optional<PrepPack> findByIdAndUserId(UUID id, UUID userId);

    List<PrepPack> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<PrepPack> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    /** Packs made since a point in time, deleted ones included: they count towards the free monthly allowance. */
    @Query(value = "select count(*) from prep_packs where user_id = :userId and created_at >= :since", nativeQuery = true)
    long countCreatedSince(UUID userId, Instant since);
}
