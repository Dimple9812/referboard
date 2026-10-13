package com.referboard.profile.repository;

import com.referboard.profile.domain.JobPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JobPreferencesRepository extends JpaRepository<JobPreferences, UUID> {
}
