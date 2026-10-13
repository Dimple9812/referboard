package com.referboard.auth.service;

import com.referboard.auth.config.AuthProperties;
import com.referboard.auth.domain.RefreshToken;
import com.referboard.auth.domain.User;
import com.referboard.auth.repository.RefreshTokenRepository;
import com.referboard.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Opaque refresh tokens: 256 random bits, stored only as a SHA-256 hash. Rotation on every use with reuse
 * detection (OAuth 2.0 Security BCP, section 4.14).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AuthProperties props;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties props, Clock clock) {
        this.repository = repository;
        this.props = props;
        this.clock = clock;
    }

    public record Issued(String rawToken, RefreshToken entity) {
    }

    public record Rotation(User user, Issued next) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued issue(User user, UUID familyId, ClientInfo client) {
        String raw = newRawToken();
        RefreshToken token = RefreshToken.issue(user, hash(raw), familyId,
                clock.instant().plus(props.jwt().refreshTokenTtl()), client.userAgent(), client.ipAddress());
        repository.save(token);
        return new Issued(raw, token);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued startFamily(User user, ClientInfo client) {
        return issue(user, UUID.randomUUID(), client);
    }

    /**
     * Validates and rotates. The reuse branch must commit the family revocation even though the caller gets
     * an error, so this runs in its own transaction and reports reuse through the return path instead of
     * throwing inside it.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken, ClientInfo client) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalid);

        if (current.isRevoked()) {
            int revoked = repository.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {} (family {}); revoked {} tokens",
                    current.getUser().getId(), current.getFamilyId(), revoked);
            throw invalid();
        }
        if (current.isExpired(now)) {
            throw invalid();
        }
        User user = current.getUser();
        if (!user.isActive() || user.getDeletedAt() != null) {
            repository.revokeFamily(current.getFamilyId(), now);
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account is disabled");
        }
        Issued next = issue(user, current.getFamilyId(), client);
        current.revoke(now, next.entity().getId());
        return new Rotation(user, next);
    }

    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHashForUpdate(hash(rawToken))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        repository.revokeAllForUser(userId, clock.instant());
    }

    /** Housekeeping: drop tokens that expired more than a week ago (kept briefly for reuse forensics). */
    @Scheduled(cron = "${referboard.auth.refresh-cleanup-cron:0 15 4 * * *}")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpiredBefore(clock.instant().minus(7, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("Purged {} expired refresh tokens", deleted);
        }
    }

    public long ttlSeconds() {
        return props.jwt().refreshTokenTtl().toSeconds();
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException invalid() {
        return ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Session expired. Please sign in again.");
    }
}
