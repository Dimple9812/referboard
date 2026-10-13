package com.referboard.auth.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request/response bodies for the auth API. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Email @Size(max = 320) String email,
            // bcrypt only uses the first 72 bytes, so cap the length rather than silently ignoring the tail.
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
            String password,
            @NotBlank @Size(max = 200) String fullName
    ) {
        @Override
        public String toString() {
            return "SignupRequest[email=" + email + ", password=****, fullName=" + fullName + "]";
        }
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(max = 72) String password
    ) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=****]";
        }
    }

    public record GoogleLoginRequest(
            @Schema(description = "ID token from Google Identity Services (the 'credential' field)")
            @NotBlank String idToken
    ) {
    }

    /**
     * Browsers send nothing: the refresh token comes from the httpOnly cookie. Non-browser clients (CLI, tests,
     * the Phase 2 extension) may pass it in the body instead.
     */
    public record RefreshRequest(String refreshToken) {
    }

    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String avatarUrl,
            String authProvider,
            boolean emailVerified,
            String role,
            Instant createdAt
    ) {
    }

    public record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            UserResponse user,
            @JsonIgnore String refreshToken,
            @JsonIgnore long refreshExpiresIn
    ) {
    }
}
