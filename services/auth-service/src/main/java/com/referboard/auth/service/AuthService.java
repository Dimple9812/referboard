package com.referboard.auth.service;

import com.referboard.auth.config.AuthProperties;
import com.referboard.auth.domain.User;
import com.referboard.auth.mapper.UserMapper;
import com.referboard.auth.repository.UserRepository;
import com.referboard.auth.web.dto.AuthDtos.AuthResponse;
import com.referboard.auth.web.dto.AuthDtos.LoginRequest;
import com.referboard.auth.web.dto.AuthDtos.SignupRequest;
import com.referboard.auth.web.dto.AuthDtos.UserResponse;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.web.ApiException;
import com.referboard.events.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Used to burn the same bcrypt time for unknown emails, so response timing doesn't reveal which exist. */
    private final String dummyHash;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final GoogleIdTokenVerifier googleVerifier;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final UserMapper mapper;
    private final AuthProperties props;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
                       RefreshTokenService refreshTokens, GoogleIdTokenVerifier googleVerifier, EventPublisher events,
                       EventJson eventJson, UserMapper mapper, AuthProperties props, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.googleVerifier = googleVerifier;
        this.events = events;
        this.eventJson = eventJson;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse signup(SignupRequest request, ClientInfo client) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = User.local(email, passwordEncoder.encode(request.password()), request.fullName().trim());
        user.registerSuccessfulLogin(clock.instant());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent signup for the same email.
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        publishRegistered(user);
        log.info("User {} signed up with email/password", user.getId());
        return tokensFor(user, refreshTokens.startFamily(user, client));
    }

    /**
     * Lockout and failure bookkeeping must be committed even when the login is rejected, hence
     * {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse login(LoginRequest request, ClientInfo client) {
        Instant now = clock.instant();
        User user = users.findByEmailIgnoreCase(normalizeEmail(request.email())).orElse(null);

        if (user == null || user.getPasswordHash() == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            if (user != null) {
                throw ApiException.unauthorized("USE_GOOGLE_SIGN_IN",
                        "This account uses Google sign-in. Continue with Google instead.");
            }
            throw badCredentials();
        }
        if (user.isLocked(now)) {
            throw ApiException.tooManyRequests("ACCOUNT_LOCKED",
                    "Too many failed attempts. Try again in a few minutes.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.registerFailedLogin(now, props.lockout().maxFailedAttempts(), props.lockout().duration());
            throw badCredentials();
        }
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account is disabled");
        }
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        user.registerSuccessfulLogin(now);
        return tokensFor(user, refreshTokens.startFamily(user, client));
    }

    /**
     * Sign in with a verified Google identity: existing Google-linked account, else link to an existing
     * account with the same (Google-verified) email, else create a new account.
     */
    @Transactional
    public AuthResponse loginWithGoogle(String idToken, ClientInfo client) {
        GoogleIdTokenVerifier.GoogleIdentity identity = googleVerifier.verify(idToken);
        String email = normalizeEmail(identity.email());

        User user = users.findByGoogleSubject(identity.subject()).orElse(null);
        boolean created = false;
        if (user == null) {
            user = users.findByEmailIgnoreCase(email).orElse(null);
            if (user != null) {
                user.linkGoogle(identity.subject(), identity.pictureUrl());
                log.info("Linked Google identity to existing user {}", user.getId());
            } else {
                user = users.save(User.google(email, identity.subject(), identity.name(), identity.pictureUrl()));
                created = true;
            }
        }
        if (!user.isActive()) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "This account is disabled");
        }
        user.registerSuccessfulLogin(clock.instant());
        if (created) {
            publishRegistered(user);
            log.info("User {} signed up with Google", user.getId());
        }
        return tokensFor(user, refreshTokens.startFamily(user, client));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawRefreshToken, ClientInfo client) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Session expired. Please sign in again.");
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken, client);
        return tokensFor(rotation.user(), rotation.next());
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.revoke(rawRefreshToken);
        }
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return users.findById(userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> ApiException.notFound("User", userId));
    }

    private AuthResponse tokensFor(User user, RefreshTokenService.Issued refresh) {
        return new AuthResponse(accessTokens.issue(user), "Bearer", accessTokens.ttlSeconds(),
                mapper.toResponse(user), refresh.rawToken(), refreshTokens.ttlSeconds());
    }

    private void publishRegistered(User user) {
        events.publish(new UserRegisteredEvent(eventJson.newMetadata("UserRegistered"), user.getId(),
                user.getEmail(), user.getFullName(), user.getAuthProvider().name()));
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException badCredentials() {
        return ApiException.unauthorized("BAD_CREDENTIALS", "Incorrect email or password");
    }
}
