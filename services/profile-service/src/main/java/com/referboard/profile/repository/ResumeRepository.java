package com.referboard.profile.repository;

import com.referboard.profile.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

    Optional<Resume> findByIdAndUserId(UUID id, UUID userId);

    List<Resume> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Resume> findFirstByUserIdAndSha256(UUID userId, String sha256);

    Optional<Resume> findByUserIdAndPrimaryTrue(UUID userId);

    long countByUserId(UUID userId);

    /** Must run (and flush) before marking another resume primary: a partial unique index allows one per user. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update Resume r set r.primary = false where r.userId = :userId and r.primary = true")
    int clearPrimary(UUID userId);
}
