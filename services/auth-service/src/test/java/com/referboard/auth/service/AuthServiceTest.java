package com.referboard.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.auth.config.AuthProperties;
import com.referboard.auth.domain.RefreshToken;
import com.referboard.auth.domain.User;
import com.referboard.auth.mapper.UserMapper;
import com.referboard.auth.repository.UserRepository;
import com.referboard.auth.web.dto.AuthDtos.LoginRequest;
import com.referboard.auth.web.dto.AuthDtos.SignupRequest;
import com.referboard.auth.web.dto.AuthDtos.UserResponse;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.web.ApiException;
import com.referboard.events.UserRegisteredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final AccessTokenService accessTokens = mock(AccessTokenService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final GoogleIdTokenVerifier google = mock(GoogleIdTokenVerifier.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final UserMapper mapper = mock(UserMapper.class);
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuthProperties props = new AuthProperties(
            new AuthProperties.Jwt("iss", "aud", Duration.ofMinutes(15), Duration.ofDays(30), null, null, "x", null),
            new AuthProperties.Google("client-id", "https://example.invalid/certs"),
            new AuthProperties.RefreshCookie("rb_refresh", "/api/auth", true, "Strict"),
            new AuthProperties.Lockout(3, Duration.ofMinutes(15)));

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, encoder, accessTokens, refreshTokens, google, events,
                new EventJson(new ObjectMapper(), "auth-service"), mapper, props, clock);
        when(accessTokens.issue(any())).thenReturn("access-token");
        when(mapper.toResponse(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), null, u.getAuthProvider().name(),
                    u.isEmailVerified(), u.getRole().name(), null, "FREE", null);
        });
        when(refreshTokens.startFamily(any(), any())).thenAnswer(inv -> new RefreshTokenService.Issued("raw-refresh",
                RefreshToken.issue(inv.getArgument(0), "h", UUID.randomUUID(), NOW.plusSeconds(60), null, null)));
    }

    @Test
    void signupNormalisesEmailHashesPasswordAndPublishesEvent() {
        when(users.existsByEmailIgnoreCase("priya@example.com")).thenReturn(false);
        when(users.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.signup(new SignupRequest("  Priya@Example.com ", "s3cretpass", " Priya S "),
                ClientInfo.UNKNOWN);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("priya@example.com");
        assertThat(saved.getValue().getFullName()).isEqualTo("Priya S");
        assertThat(saved.getValue().getPasswordHash()).startsWith("{bcrypt}").doesNotContain("s3cretpass");
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh");

        ArgumentCaptor<UserRegisteredEvent> event = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().email()).isEqualTo("priya@example.com");
        assertThat(event.getValue().authProvider()).isEqualTo("LOCAL");
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(users.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.signup(new SignupRequest("taken@example.com", "s3cretpass", "X"),
                ClientInfo.UNKNOWN))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "EMAIL_TAKEN");
        verify(events, never()).publish(any());
    }

    @Test
    void loginWithWrongPasswordCountsFailuresAndLocksAccount() {
        User user = User.local("a@example.com", encoder.encode("right-pass1"), "A");
        when(users.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.of(user));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("a@example.com", "wrong-pass1"), ClientInfo.UNKNOWN))
                    .hasFieldOrPropertyWithValue("code", "BAD_CREDENTIALS");
        }
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));

        // Even the correct password is refused while locked.
        assertThatThrownBy(() -> service.login(new LoginRequest("a@example.com", "right-pass1"), ClientInfo.UNKNOWN))
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_LOCKED");
    }

    @Test
    void successfulLoginResetsFailureCounter() {
        User user = User.local("a@example.com", encoder.encode("right-pass1"), "A");
        user.registerFailedLogin(NOW, 3, Duration.ofMinutes(15));
        when(users.findByEmailIgnoreCase("a@example.com")).thenReturn(Optional.of(user));

        service.login(new LoginRequest("A@example.com", "right-pass1"), ClientInfo.UNKNOWN);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void unknownEmailGetsSameErrorAsWrongPassword() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "whatever1"), ClientInfo.UNKNOWN))
                .hasFieldOrPropertyWithValue("code", "BAD_CREDENTIALS");
    }

    @Test
    void googleLoginLinksExistingAccountWithSameEmail() {
        User existing = User.local("dev@example.com", encoder.encode("pass12345"), "Dev");
        when(google.verify("id-token")).thenReturn(
                new GoogleIdTokenVerifier.GoogleIdentity("google-sub-1", "Dev@Example.com", "Dev", "https://pic"));
        when(users.findByGoogleSubject("google-sub-1")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("dev@example.com")).thenReturn(Optional.of(existing));

        service.loginWithGoogle("id-token", ClientInfo.UNKNOWN);

        assertThat(existing.getGoogleSubject()).isEqualTo("google-sub-1");
        assertThat(existing.isEmailVerified()).isTrue();
        verify(users, never()).save(any());
        verify(events, never()).publish(any());
    }

    @Test
    void googleLoginCreatesNewAccountAndPublishesEvent() {
        when(google.verify("id-token")).thenReturn(
                new GoogleIdTokenVerifier.GoogleIdentity("google-sub-2", "new@example.com", "New Person", null));
        when(users.findByGoogleSubject("google-sub-2")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("new@example.com")).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.loginWithGoogle("id-token", ClientInfo.UNKNOWN);

        assertThat(response.user().authProvider()).isEqualTo("GOOGLE");
        ArgumentCaptor<UserRegisteredEvent> event = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().authProvider()).isEqualTo("GOOGLE");
    }
}
