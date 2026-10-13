package com.referboard.auth.service;

import com.referboard.auth.config.AuthProperties;
import com.referboard.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Verifies Google ID tokens obtained by the frontend through Google Identity Services: RS256 signature against
 * Google's published keys, issuer, audience (our client id), expiry and {@code email_verified}.
 *
 * <p>This keeps the backend stateless: no OAuth redirect/session dance through the gateway, and the same
 * endpoint works for the Phase 2 Chrome extension.
 */
@Component
public class GoogleIdTokenVerifier {

    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final AuthProperties.Google config;
    private final JwtDecoder decoder;

    @Autowired
    public GoogleIdTokenVerifier(AuthProperties props) {
        this.config = props.google();
        this.decoder = config.enabled() ? buildDecoder(config) : null;
    }

    /** Package-private for tests: verify with a supplied decoder (e.g. one backed by a local test key). */
    GoogleIdTokenVerifier(AuthProperties props, JwtDecoder decoder) {
        this.config = props.google();
        this.decoder = decoder;
    }

    public record GoogleIdentity(String subject, String email, String name, String pictureUrl) {
    }

    public GoogleIdentity verify(String idToken) {
        if (decoder == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_LOGIN_DISABLED",
                    "Google sign-in is not configured on this server");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            throw ApiException.unauthorized("INVALID_GOOGLE_TOKEN", "Google sign-in failed. Please try again.");
        }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw ApiException.unauthorized("GOOGLE_EMAIL_UNVERIFIED", "Your Google email address is not verified");
        }
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        return new GoogleIdentity(jwt.getSubject(), email, name == null || name.isBlank() ? email : name,
                jwt.getClaimAsString("picture"));
    }

    static OAuth2TokenValidator<Jwt> validators(String clientId) {
        OAuth2TokenValidator<Jwt> issuer = jwt -> ISSUERS.contains(String.valueOf(jwt.getClaims().get("iss")))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Bad issuer", null));
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Bad audience", null));
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, audience);
    }

    private static JwtDecoder buildDecoder(AuthProperties.Google google) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(google.jwkSetUri()).build();
        decoder.setJwtValidator(validators(google.clientId()));
        return decoder;
    }
}
