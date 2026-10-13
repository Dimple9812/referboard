package com.referboard.referral.repository;

import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.ContactStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    Optional<Contact> findByIdAndUserId(UUID id, UUID userId);

    List<Contact> findByUserIdAndJobIdOrderByCreatedAtAsc(UUID userId, UUID jobId);

    long countByUserIdAndJobId(UUID userId, UUID jobId);

    Page<Contact> findByUserId(UUID userId, Pageable pageable);

    Page<Contact> findByUserIdAndStatusIn(UUID userId, Collection<ContactStatus> statuses, Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "select c.status, count(c) from Contact c where c.userId = :userId group by c.status")
    List<Object[]> countByStatus(UUID userId);

    boolean existsByUserIdAndJobIdAndProfileUrlIgnoreCase(UUID userId, UUID jobId, String profileUrl);
}
