package com.referboard.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyConfigTest {

    @Test
    void generatesKeysOnceAndReusesThemAcrossRestarts(@TempDir Path dir) throws Exception {
        var first = JwtKeyConfig.loadKeyPair(jwt(null, null, dir));
        var second = JwtKeyConfig.loadKeyPair(jwt(null, null, dir));

        assertThat(Files.exists(dir.resolve("jwt-private.pem"))).isTrue();
        assertThat(second.getPublic().getEncoded()).isEqualTo(first.getPublic().getEncoded());
    }

    @Test
    void readsPemAndBase64EncodedPemFromConfig(@TempDir Path dir) throws Exception {
        JwtKeyConfig.loadKeyPair(jwt(null, null, dir));
        String privatePem = Files.readString(dir.resolve("jwt-private.pem"));
        String publicPem = Files.readString(dir.resolve("jwt-public.pem"));
        String publicB64 = Base64.getEncoder().encodeToString(publicPem.getBytes(StandardCharsets.UTF_8));

        var pair = JwtKeyConfig.loadKeyPair(jwt(privatePem, publicB64, dir.resolve("unused")));

        assertThat(pair.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(Files.exists(dir.resolve("unused"))).isFalse();
    }

    @Test
    void refusesHalfConfiguredKeys(@TempDir Path dir) {
        assertThatThrownBy(() -> JwtKeyConfig.loadKeyPair(jwt("something", null, dir)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AuthProperties.Jwt jwt(String priv, String pub, Path dir) {
        return new AuthProperties.Jwt("iss", "aud", Duration.ofMinutes(15), Duration.ofDays(30), priv, pub,
                dir.toString(), null);
    }
}
