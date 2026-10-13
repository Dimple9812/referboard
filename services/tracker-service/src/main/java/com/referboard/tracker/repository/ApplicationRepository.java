package com.referboard.tracker.repository;

import com.referboard.tracker.domain.Application;
import com.referboard.tracker.domain.Stage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    Optional<Application> findByIdAndUserId(UUID id, UUID userId);

    Optional<Application> findByUserIdAndJobId(UUID userId, UUID jobId);

    List<Application> findByUserIdOrderByPositionAsc(UUID userId);

    List<Application> findByUserIdAndStageOrderByPositionAsc(UUID userId, Stage stage);

    List<Application> findByJobIdAndJobClosedAtIsNull(UUID jobId);

    long countByUserId(UUID userId);

    @Query("select min(a.position) from Application a where a.userId = :userId and a.stage = :stage")
    Double minPosition(UUID userId, Stage stage);
}
