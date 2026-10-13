package com.referboard.auth.web;

import com.referboard.auth.service.AuthService;
import com.referboard.auth.service.ClientInfo;
import com.referboard.auth.web.dto.AuthDtos.AuthResponse;
import com.referboard.auth.web.dto.AuthDtos.GoogleLoginRequest;
import com.referboard.auth.web.dto.AuthDtos.LoginRequest;
import com.referboard.auth.web.dto.AuthDtos.RefreshRequest;
import com.referboard.auth.web.dto.AuthDtos.SignupRequest;
import com.referboard.auth.web.dto.AuthDtos.UserResponse;
import com.referboard.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookies cookies;

    public AuthController(AuthService authService, RefreshCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/signup")
    @Operation(summary = "Create an account with email and password")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        return withRefreshCookie(HttpStatus.CREATED, authService.signup(request, client(http)));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with email and password")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return withRefreshCookie(HttpStatus.OK, authService.login(request, client(http)));
    }

    @PostMapping("/google")
    @Operation(summary = "Sign in or sign up with a Google ID token")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleLoginRequest request, HttpServletRequest http) {
        return withRefreshCookie(HttpStatus.OK, authService.loginWithGoogle(request.idToken(), client(http)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token (rotates the refresh token)")
    public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) RefreshRequest body,
                                                HttpServletRequest http) {
        String raw = cookies.read(http);
        if (raw == null && body != null) {
            raw = body.refreshToken();
        }
        return withRefreshCookie(HttpStatus.OK, authService.refresh(raw, client(http)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke this session's refresh token and clear the cookie")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body, HttpServletRequest http) {
        String raw = cookies.read(http);
        if (raw == null && body != null) {
            raw = body.refreshToken();
        }
        authService.logout(raw);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Current user", security = @SecurityRequirement(name = "bearerAuth"))
    public UserResponse me() {
        return authService.me(CurrentUser.id());
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(HttpStatus status, AuthResponse response) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE,
                        cookies.create(response.refreshToken(), response.refreshExpiresIn()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    private static ClientInfo client(HttpServletRequest request) {
        return new ClientInfo(request.getHeader(HttpHeaders.USER_AGENT), request.getRemoteAddr());
    }
}
