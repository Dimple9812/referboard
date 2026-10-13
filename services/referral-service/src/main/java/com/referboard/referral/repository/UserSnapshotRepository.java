package com.referboard.referral.repository;

import com.referboard.referral.domain.UserSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserSnapshotRepository extends JpaRepository<UserSnapshot, UUID> {
}
