package com.referboard.profile.repository;

import com.referboard.profile.domain.Profile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    /** Atomic create-if-missing: safe when two requests for a brand-new user race (no duplicate-key error). */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into profiles (user_id) values (:userId) on conflict (user_id) do nothing", nativeQuery = true)
    int insertIfAbsent(UUID userId);

    /**
     * Row lock for the rest of the transaction, so concurrent writers to one profile (resume parse finishing while
     * the user saves preferences) run one after the other instead of overwriting each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Profile p where p.userId = :userId")
    Optional<Profile> findForUpdate(UUID userId);
}
