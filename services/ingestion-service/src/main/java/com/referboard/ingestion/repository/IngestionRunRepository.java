package com.referboard.ingestion.repository;

import com.referboard.ingestion.domain.IngestionRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {

    List<IngestionRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
