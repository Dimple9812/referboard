package com.referboard.notification.repository;

import com.referboard.notification.domain.EmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {

    /** Due emails, row-locked; other instances skip them. */
    @Query(value = """
            select * from email_deliveries where status = 'QUEUED' and next_attempt_at <= :now
            order by next_attempt_at limit :limit for update skip locked
            """, nativeQuery = true)
    List<EmailDelivery> lockDue(Instant now, int limit);

    long countByUserIdAndStatusInAndCreatedAtAfter(UUID userId, Collection<EmailDelivery.Status> statuses,
                                                   Instant after);

    List<EmailDelivery> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
