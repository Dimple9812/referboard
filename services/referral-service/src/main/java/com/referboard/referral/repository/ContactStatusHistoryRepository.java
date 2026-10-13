package com.referboard.referral.repository;

import com.referboard.referral.domain.ContactStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContactStatusHistoryRepository extends JpaRepository<ContactStatusHistory, UUID> {

    List<ContactStatusHistory> findByContactIdOrderByChangedAtAsc(UUID contactId);
}
