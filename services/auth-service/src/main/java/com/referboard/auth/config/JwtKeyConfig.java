package com.referboard.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.referboard.common.security.JwtValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RS256 signing keys. auth-service is the only holder of the private key; the gateway and every other service
 * validate tokens with the public key fetched from {@code /.well-known/jwks.json}.
 *
 * <p>Key source, in order: {@code JWT_PRIVATE_KEY}/{@code JWT_PUBLIC_KEY} env vars (production), then PEM files in
 * {@code JWT_KEY_DIR}, generated on first start (local dev convenience).
 */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    public RSAKey signingKey(AuthProperties props) throws Exception {
        KeyPair pair = loadKeyPair(props.jwt());
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        RSAKey key = new RSAKey.Builder(publicKey)
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyIDFromThumbprint()
                .build();
        log.info("JWT signing key loaded (kid={})", key.getKeyID());
        return key;
    }

    @Bean
    public JWKSet publicJwkSet(RSAKey signingKey) {
        return new JWKSet(signingKey.toPublicJWK());
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey signingKey) {
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(signingKey));
        return new NimbusJwtEncoder(source);
    }

    /** auth-service validates its own access tokens (for /me) locally, without an HTTP round trip. */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey signingKey, AuthProperties props) throws Exception {
        return JwtValidation.withValidators(NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build(),
                props.jwt().issuer(), props.jwt().audience());
    }

    static KeyPair loadKeyPair(AuthProperties.Jwt jwt) throws GeneralSecurityException, IOException {
        if (notBlank(jwt.privateKey()) && notBlank(jwt.publicKey())) {
            return new KeyPair(parsePublic(jwt.publicKey()), parsePrivate(jwt.privateKey()));
        }
        if (notBlank(jwt.privateKey()) != notBlank(jwt.publicKey())) {
            throw new IllegalStateException("Set both JWT_PRIVATE_KEY and JWT_PUBLIC_KEY, or neither");
        }
        Path dir = Path.of(jwt.keyDir());
        Path privatePath = dir.resolve("jwt-private.pem");
        Path publicPath = dir.resolve("jwt-public.pem");
        if (Files.exists(privatePath) && Files.exists(publicPath)) {
            return new KeyPair(parsePublic(Files.readString(publicPath)), parsePrivate(Files.readString(privatePath)));
        }
        log.warn("No JWT keys configured; generating a development key pair in {}. "
                + "Set JWT_PRIVATE_KEY / JWT_PUBLIC_KEY in production.", dir.toAbsolutePath());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Files.createDirectories(dir);
        Files.writeString(privatePath, toPem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(publicPath, toPem("PUBLIC KEY", pair.getPublic().getEncoded()));
        try {
            Files.setPosixFilePermissions(privatePath, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system
        }
        return pair;
    }

    static RSAPrivateKey parsePrivate(String pemOrBase64) throws GeneralSecurityException {
        byte[] der = decodePem(pemOrBase64, "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    static RSAPublicKey parsePublic(String pemOrBase64) throws GeneralSecurityException {
        byte[] der = decodePem(pemOrBase64, "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** Accepts a PEM block, or the whole PEM base64-encoded on one line (handy for env vars). */
    private static byte[] decodePem(String value, String type) {
        String text = value.trim();
        if (!text.contains("-----BEGIN")) {
            String decoded = new String(Base64.getMimeDecoder().decode(text), StandardCharsets.UTF_8);
            if (decoded.contains("-----BEGIN")) {
                text = decoded;
            }
        }
        String body = text
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String toPem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
