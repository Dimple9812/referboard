package com.referboard.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.referboard.auth.config.AuthProperties;
import com.referboard.common.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Signs tokens with a local key that stands in for Google's, then runs them through the real validators. */
class GoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "123.apps.googleusercontent.com";
    private static RSAKey googleKey;
    private static GoogleIdTokenVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("g1").algorithm(JWSAlgorithm.RS256).generate();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(googleKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(GoogleIdTokenVerifier.validators(CLIENT_ID));
        AuthProperties props = new AuthProperties(
                new AuthProperties.Jwt("iss", "aud", Duration.ofMinutes(15), Duration.ofDays(30), null, null, "x"),
                new AuthProperties.Google(CLIENT_ID, "https://unused.invalid"),
                new AuthProperties.RefreshCookie("rb_refresh", "/api/auth", true, "Strict"),
                new AuthProperties.Lockout(5, Duration.ofMinutes(15)));
        verifier = new GoogleIdTokenVerifier(props, decoder);
    }

    @Test
    void acceptsValidToken() {
        var identity = verifier.verify(token("https://accounts.google.com", CLIENT_ID, true));

        assertThat(identity.subject()).isEqualTo("google-123");
        assertThat(identity.email()).isEqualTo("dev@gmail.com");
        assertThat(identity.name()).isEqualTo("Dev Person");
    }

    @Test
    void rejectsTokenForAnotherClient() {
        assertThatThrownBy(() -> verifier.verify(token("https://accounts.google.com", "someone-else", true)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void rejectsForeignIssuer() {
        assertThatThrownBy(() -> verifier.verify(token("https://evil.example.com", CLIENT_ID, true)))
                .hasFieldOrPropertyWithValue("code", "INVALID_GOOGLE_TOKEN");
    }

    @Test
    void rejectsUnverifiedEmail() {
        assertThatThrownBy(() -> verifier.verify(token("accounts.google.com", CLIENT_ID, false)))
                .hasFieldOrPropertyWithValue("code", "GOOGLE_EMAIL_UNVERIFIED");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .hasFieldOrPropertyWithValue("code", "INVALID_GOOGLE_TOKEN");
    }

    private static String token(String issuer, String audience, boolean emailVerified) {
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(googleKey)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("google-123")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claims(c -> c.putAll(Map.of("email", "dev@gmail.com", "email_verified", emailVerified,
                        "name", "Dev Person")))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
