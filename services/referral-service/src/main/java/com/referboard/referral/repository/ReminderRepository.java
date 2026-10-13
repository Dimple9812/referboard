package com.referboard.referral.repository;

import com.referboard.referral.domain.ContactStatus;
import com.referboard.referral.domain.Reminder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {

    /** Due reminders, row-locked and skipped by concurrent scanners (several instances can run the scheduler). */
    @Query(value = """
            select * from reminders where status = 'PENDING' and due_at <= :now
            order by due_at limit :limit for update skip locked
            """, nativeQuery = true)
    List<Reminder> lockDue(Instant now, int limit);

    List<Reminder> findByUserIdAndStatusInOrderByDueAtDesc(UUID userId, Collection<Reminder.Status> statuses,
                                                           Pageable pageable);

    List<Reminder> findByContactIdAndUserIdOrderByDueAtDesc(UUID contactId, UUID userId);

    Optional<Reminder> findByIdAndUserId(UUID id, UUID userId);

    /** Fired reminders for an earlier status are satisfied once the contact moves on (e.g. CONNECTED → asked). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Reminder r set r.status = com.referboard.referral.domain.Reminder.Status.DONE
            where r.contactId = :contactId and r.status = com.referboard.referral.domain.Reminder.Status.FIRED
            and r.triggerStatus <> :current
            """)
    int completeFiredExcept(UUID contactId, ContactStatus current);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reminder r set r.status = com.referboard.referral.domain.Reminder.Status.DISMISSED
            where r.contactId = :contactId and r.status = com.referboard.referral.domain.Reminder.Status.FIRED
            """)
    int dismissFired(UUID contactId);

    long countByUserIdAndStatus(UUID userId, Reminder.Status status);

    /** A status change (or delete) makes reminders scheduled for the previous status pointless. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Reminder r set r.status = com.referboard.referral.domain.Reminder.Status.OBSOLETE
            where r.contactId = :contactId and r.status = com.referboard.referral.domain.Reminder.Status.PENDING
            """)
    int obsoleteAllPending(UUID contactId);
}
