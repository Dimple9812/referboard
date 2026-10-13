package com.referboard.ingestion.repository;

import com.referboard.ingestion.domain.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceRepository extends JpaRepository<Source, String> {

    List<Source> findByEnabledTrueOrderBySourceKey();

    List<Source> findAllByOrderByStatusAscSourceKeyAsc();
}
