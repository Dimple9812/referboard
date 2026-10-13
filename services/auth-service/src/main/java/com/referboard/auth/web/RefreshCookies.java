package com.referboard.auth.web;

import com.referboard.auth.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

/**
 * The refresh token lives only in an httpOnly, SameSite=Strict cookie scoped to {@code /api/auth}, so page
 * JavaScript (and therefore XSS) can never read it, and other sites can't trigger a refresh.
 */
@Component
public class RefreshCookies {

    private final AuthProperties.RefreshCookie config;

    public RefreshCookies(AuthProperties props) {
        this.config = props.refreshCookie();
    }

    public ResponseCookie create(String rawToken, long maxAgeSeconds) {
        return base(rawToken).maxAge(Duration.ofSeconds(maxAgeSeconds)).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(c -> config.name().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(config.name(), value)
                .httpOnly(true)
                .secure(config.secure())
                .sameSite(config.sameSite())
                .path(config.path());
    }
}
