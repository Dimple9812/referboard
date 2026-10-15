package com.referboard.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "referboard.auth")
public record AuthProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Google google,
        @Valid @NotNull RefreshCookie refreshCookie,
        @Valid @NotNull Lockout lockout
) {
    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            String privateKey,
            String publicKey,
            @NotBlank String keyDir,
            Duration refreshReuseGrace
    ) {
        /**
         * How long a just-rotated refresh token may be presented again without being treated as theft. Covers a
         * response lost in flight (page reload or tab close mid-refresh) and two tabs refreshing at once.
         */
        public Jwt {
            if (refreshReuseGrace == null) {
                refreshReuseGrace = Duration.ofSeconds(30);
            }
        }
    }

    /** {@code clientId} empty disables Google sign-in (endpoint answers 503). */
    public record Google(String clientId, @NotBlank String jwkSetUri) {
        public boolean enabled() {
            return clientId != null && !clientId.isBlank();
        }
    }

    public record RefreshCookie(@NotBlank String name, @NotBlank String path, boolean secure, @NotBlank String sameSite) {
    }

    public record Lockout(@Min(1) int maxFailedAttempts, @NotNull Duration duration) {
    }
}
