package com.referboard.auth.service;

import com.referboard.auth.domain.RefreshToken;
import com.referboard.auth.domain.User;
import com.referboard.auth.mapper.UserMapper;
import com.referboard.auth.repository.RefreshTokenRepository;
import com.referboard.auth.repository.UserRepository;
import com.referboard.auth.web.dto.ExtensionDtos.ExtensionConnection;
import com.referboard.auth.web.dto.ExtensionDtos.ExtensionTokens;
import com.referboard.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Sessions for the browser extension. The signed-in web app asks for one on the user's explicit "Connect" and hands
 * it to the extension; the extension keeps the refresh token and rotates it in the request body. Each connection is
 * its own token family, listed in Settings and revocable there or from the extension. Web and extension tokens only
 * work on their own refresh endpoint.
 */
@Service
public class ExtensionSessionService {

    private static final Logger log = LoggerFactory.getLogger(ExtensionSessionService.class);
    static final int MAX_CONNECTIONS = 5;

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final RefreshTokenRepository tokens;
    private final AccessTokenService accessTokens;
    private final UserMapper mapper;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ExtensionSessionService(UserRepository users, RefreshTokenService refreshTokens, RefreshTokenRepository tokens,
                                   AccessTokenService accessTokens, UserMapper mapper, JdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.accessTokens = accessTokens;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ExtensionTokens connect(UUID userId, ClientInfo client) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account is disabled");
        }
        // Reconnecting from new browsers shouldn't pile up live tokens: keep the newest few.
        List<UUID> active = activeFamilies(userId);
        for (UUID family : active.subList(Math.min(active.size(), MAX_CONNECTIONS - 1), active.size())) {
            tokens.revokeFamily(family, clock.instant());
        }
        RefreshTokenService.Issued issued = refreshTokens.startFamily(user, client, RefreshToken.Client.EXTENSION);
        log.info("Extension connected for user {} (family {})", userId, issued.entity().getFamilyId());
        return tokensFor(user, issued);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ExtensionTokens refresh(String rawToken, ClientInfo client) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Connect the extension again.");
        }
        RefreshTokenService.Rotation r = refreshTokens.rotate(rawToken, client, RefreshToken.Client.EXTENSION);
        return tokensFor(r.user(), r.next());
    }

    @Transactional
    public void disconnect(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            refreshTokens.revoke(rawToken);
        }
    }

    @Transactional(readOnly = true)
    public List<ExtensionConnection> connections(UUID userId) {
        return jdbc.query("""
                select family_id, min(created_at) as connected_at, max(created_at) as last_used_at,
                       (array_agg(user_agent order by created_at desc))[1] as user_agent
                from refresh_tokens
                where user_id = ? and client = 'EXTENSION' and family_id in (
                    select family_id from refresh_tokens
                    where user_id = ? and client = 'EXTENSION' and revoked_at is null and expires_at > ?)
                group by family_id
                order by last_used_at desc
                """, (rs, i) -> new ExtensionConnection(rs.getObject("family_id", UUID.class),
                        rs.getTimestamp("connected_at").toInstant(), rs.getTimestamp("last_used_at").toInstant(),
                        browser(rs.getString("user_agent"))),
                userId, userId, Timestamp.from(clock.instant()));
    }

    @Transactional
    public void revoke(UUID userId, UUID familyId) {
        int n = jdbc.update("""
                update refresh_tokens set revoked_at = ? where user_id = ? and family_id = ? and client = 'EXTENSION'
                and revoked_at is null
                """, Timestamp.from(clock.instant()), userId, familyId);
        if (n == 0 && connections(userId).stream().noneMatch(c -> c.id().equals(familyId))) {
            throw ApiException.notFound("Extension connection", familyId);
        }
    }

    private List<UUID> activeFamilies(UUID userId) {
        return connections(userId).stream().map(ExtensionConnection::id).toList();
    }

    private ExtensionTokens tokensFor(User user, RefreshTokenService.Issued refresh) {
        return new ExtensionTokens(accessTokens.issue(user), "Bearer", accessTokens.ttlSeconds(), refresh.rawToken(),
                refreshTokens.ttlSeconds(), mapper.toResponse(user));
    }

    /** "Chrome on macOS" from a user agent; enough to tell connections apart in Settings. */
    static String browser(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Browser";
        }
        String b = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("Brave") ? "Brave"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Firefox/") ? "Firefox" : "Browser";
        String os = ua.contains("Windows") ? "Windows" : ua.contains("Mac OS X") ? "macOS" : ua.contains("Android")
                ? "Android" : ua.contains("CrOS") ? "ChromeOS" : ua.contains("Linux") ? "Linux" : null;
        return os == null ? b : b + " on " + os;
    }
}
