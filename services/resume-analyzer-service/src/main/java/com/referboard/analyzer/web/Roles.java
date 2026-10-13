package com.referboard.analyzer.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/** Paid (PRO) and ADMIN users have no monthly analysis limit. */
final class Roles {

    private static final Set<String> UNLIMITED = Set.of("ROLE_PRO", "ROLE_ADMIN");

    private Roles() {
    }

    static boolean unlimited() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> UNLIMITED.contains(a.getAuthority()));
    }
}
