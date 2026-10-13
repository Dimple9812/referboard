package com.referboard.common.security;

import com.referboard.common.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Access to the authenticated user. The JWT {@code sub} claim is the user's UUID issued by auth-service, so
 * every service scopes its queries by this id and never trusts a user id from the request body or path.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
    }

    public static String email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("email");
        }
        throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
    }
}
