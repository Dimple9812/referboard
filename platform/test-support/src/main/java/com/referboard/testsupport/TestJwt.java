package com.referboard.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.referboard.common.security.JwtValidation;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stands in for auth-service in integration tests: mints RS256 access tokens shaped exactly like the real ones
 * and provides a decoder with the production validators (iss, aud, exp). Register {@link #decoder()} as the
 * {@code JwtDecoder} bean in a {@code @TestConfiguration}.
 */
public final class TestJwt {

    public static final String ISSUER = "referboard-auth";
    public static final String AUDIENCE = "referboard";

    private static final RSAKey KEY = generate();

    private TestJwt() {
    }

    public static JwtDecoder decoder() {
        try {
            return JwtValidation.withValidators(NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build(),
                    ISSUER, AUDIENCE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String tokenFor(UUID userId) {
        return token(userId, ISSUER, AUDIENCE, Instant.now().plusSeconds(900));
    }

    public static String token(UUID userId, String issuer, String audience, Instant expiresAt) {
        // Issued 15 minutes before expiry, so an already-expired token is still well-formed.
        Instant issued = expiresAt.minusSeconds(900);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(issued)
                .notBefore(issued)
                .expiresAt(expiresAt)
                .claim("email", userId + "@test.referboard")
                .claim("name", "Test User")
                .claim("roles", List.of("USER"))
                .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").algorithm(JWSAlgorithm.RS256).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
