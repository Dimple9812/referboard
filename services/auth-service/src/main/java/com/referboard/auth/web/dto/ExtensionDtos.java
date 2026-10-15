package com.referboard.auth.web.dto;

import com.referboard.auth.web.dto.AuthDtos.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ExtensionDtos {

    private ExtensionDtos() {
    }

    @Schema(description = "Tokens for the browser extension. Unlike web sign-in, the refresh token is in the body: "
            + "the extension stores it in its own storage and sends it back to /api/auth/extension/refresh.")
    public record ExtensionTokens(String accessToken, String tokenType, long expiresIn, String refreshToken,
                                  long refreshExpiresIn, UserResponse user) {
    }

    public record ExtensionRefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    @Schema(description = "One connected extension (one browser profile)")
    public record ExtensionConnection(UUID id, Instant connectedAt, Instant lastUsedAt, String browser) {
    }
}
