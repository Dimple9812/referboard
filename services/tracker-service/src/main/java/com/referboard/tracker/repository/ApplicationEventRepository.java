package com.referboard.tracker.repository;

import com.referboard.tracker.domain.ApplicationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, UUID> {

    List<ApplicationEvent> findByApplicationIdOrderBySeqAsc(UUID applicationId);
}
