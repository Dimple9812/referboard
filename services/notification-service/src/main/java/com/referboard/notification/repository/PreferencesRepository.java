package com.referboard.notification.repository;

import com.referboard.notification.domain.NotificationPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PreferencesRepository extends JpaRepository<NotificationPreferences, UUID> {

    Optional<NotificationPreferences> findByUnsubscribeToken(UUID token);
}
