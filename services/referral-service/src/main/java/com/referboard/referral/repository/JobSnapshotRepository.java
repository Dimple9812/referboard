package com.referboard.referral.repository;

import com.referboard.referral.domain.JobSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JobSnapshotRepository extends JpaRepository<JobSnapshot, UUID> {
}
