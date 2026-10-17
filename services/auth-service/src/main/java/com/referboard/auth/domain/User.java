package com.referboard.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    public enum AuthProvider { LOCAL, GOOGLE }

    /** PRO = paid plan (e.g. unlimited JD analyses). */
    public enum Role { USER, PRO, ADMIN }

    public enum Status { ACTIVE, DISABLED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider authProvider;

    private String googleSubject;

    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private int failedLoginAttempts;

    private Instant lockedUntil;

    /** End of a paid Pro pass (billing-service); null if never bought. */
    private Instant proUntil;

    private Instant proChangedAt;

    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deletedAt;

    @Version
    private long version;

    public static User local(String email, String passwordHash, String fullName) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.authProvider = AuthProvider.LOCAL;
        user.emailVerified = false;
        return user;
    }

    public static User google(String email, String googleSubject, String fullName, String avatarUrl) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = email;
        user.googleSubject = googleSubject;
        user.fullName = fullName;
        user.avatarUrl = avatarUrl;
        user.authProvider = AuthProvider.GOOGLE;
        user.emailVerified = true;
        return user;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** Counts a failed password attempt and locks the account once the limit is reached. */
    public void registerFailedLogin(Instant now, int maxAttempts, Duration lockDuration) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginAttempts = 0;
        }
    }

    public void registerSuccessfulLogin(Instant now) {
        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void linkGoogle(String subject, String avatar) {
        this.googleSubject = subject;
        this.emailVerified = true;
        if (this.avatarUrl == null) {
            this.avatarUrl = avatar;
        }
    }

    public boolean hasPro(Instant now) {
        return role == Role.PRO || role == Role.ADMIN || (proUntil != null && proUntil.isAfter(now));
    }

    /** Roles for the access token: the stored role, plus PRO while a paid pass runs. */
    public java.util.List<String> effectiveRoles(Instant now) {
        if (role == Role.USER && proUntil != null && proUntil.isAfter(now)) {
            return java.util.List.of(Role.USER.name(), Role.PRO.name());
        }
        return java.util.List.of(role.name());
    }

    /** Applies a pass change unless a newer one was already applied. Returns false when it's stale. */
    public boolean applyProChange(Instant until, Instant changedAt) {
        if (proChangedAt != null && changedAt != null && changedAt.isBefore(proChangedAt)) {
            return false;
        }
        this.proUntil = until;
        this.proChangedAt = changedAt;
        return true;
    }
}
