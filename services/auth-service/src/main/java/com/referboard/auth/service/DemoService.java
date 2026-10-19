package com.referboard.auth.service;

import com.referboard.auth.config.DemoProperties;
import com.referboard.auth.domain.User;
import com.referboard.auth.mapper.UserMapper;
import com.referboard.auth.repository.UserRepository;
import com.referboard.auth.web.dto.AuthDtos.AuthResponse;
import com.referboard.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;

/**
 * Signs visitors into one shared demo account without a password, for a public showcase site. Off unless
 * {@code DEMO_ACCOUNT_EMAIL} is set. Each visitor gets their own session (refresh-token family); the account itself
 * is shared, so it must hold only fictional data and is never an admin.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    public record Status(boolean enabled, String email) {
    }

    private final DemoProperties props;
    private final UserRepository users;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final UserMapper mapper;
    private final Clock clock;

    public DemoService(DemoProperties props, UserRepository users, AccessTokenService accessTokens,
                       RefreshTokenService refreshTokens, UserMapper mapper, Clock clock) {
        this.props = props;
        this.users = users;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Status status() {
        boolean ready = props.enabled() && users.findByEmailIgnoreCase(email()).isPresent();
        return new Status(ready, ready ? email() : null);
    }

    @Transactional
    public AuthResponse signIn(ClientInfo client) {
        if (!props.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEMO_DISABLED", "There's no demo on this ReferBoard.");
        }
        User user = users.findByEmailIgnoreCase(email()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "DEMO_NOT_READY", "The demo account hasn't been set up yet."));
        if (user.getRole() == User.Role.ADMIN) {
            // Never hand out an admin session to anonymous visitors, even if someone misconfigures the email.
            log.error("Demo sign-in refused: {} is an admin account", user.getId());
            throw ApiException.forbidden("DEMO_UNAVAILABLE", "The demo isn't available right now.");
        }
        if (!user.isActive()) {
            throw ApiException.forbidden("DEMO_UNAVAILABLE", "The demo isn't available right now.");
        }
        user.registerSuccessfulLogin(clock.instant());
        RefreshTokenService.Issued refresh = refreshTokens.startFamily(user, client);
        return new AuthResponse(accessTokens.issue(user), "Bearer", accessTokens.ttlSeconds(),
                mapper.toResponse(user), refresh.rawToken(), refreshTokens.ttlSeconds());
    }

    private String email() {
        return props.accountEmail().trim().toLowerCase(Locale.ROOT);
    }
}
