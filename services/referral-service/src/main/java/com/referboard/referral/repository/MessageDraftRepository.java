package com.referboard.referral.repository;

import com.referboard.referral.domain.MessageDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageDraftRepository extends JpaRepository<MessageDraft, UUID> {

    List<MessageDraft> findByContactIdAndUserIdOrderByCreatedAtDesc(UUID contactId, UUID userId);

    long countByUserIdAndModelNotAndCreatedAtAfter(UUID userId, String model, java.time.Instant after);

    Optional<MessageDraft> findByIdAndUserId(UUID id, UUID userId);
}
