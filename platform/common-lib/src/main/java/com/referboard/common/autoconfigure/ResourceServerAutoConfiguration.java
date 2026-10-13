package com.referboard.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.security.JwtValidation;
import com.referboard.common.security.ResourceServerSecurity;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Makes every service a JWT resource server with one block of config:
 * <pre>
 * referboard.security.jwt:
 *   jwk-set-uri: http://auth-service:8081/.well-known/jwks.json
 *   issuer: referboard-auth
 *   audience: referboard
 * </pre>
 * Services that need extra public endpoints declare their own {@code SecurityFilterChain} (built on
 * {@link ResourceServerSecurity#applyDefaults}) and this default backs off.
 */
@AutoConfiguration(before = {OAuth2ResourceServerAutoConfiguration.class, SecurityAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({JwtDecoder.class, SecurityFilterChain.class})
@EnableConfigurationProperties(ResourceServerAutoConfiguration.JwtProperties.class)
@EnableWebSecurity
public class ResourceServerAutoConfiguration {

    @ConfigurationProperties(prefix = "referboard.security.jwt")
    public record JwtProperties(String jwkSetUri, String issuer, String audience) {
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnProperty(prefix = "referboard.security.jwt", name = "jwk-set-uri")
    public JwtDecoder referboardJwtDecoder(JwtProperties props) {
        // Keys are fetched lazily on first use and cached by Nimbus; key rotation is picked up automatically.
        return JwtValidation.withValidators(NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build(),
                required(props.issuer(), "issuer"), required(props.audience(), "audience"));
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain referboardDefaultSecurityFilterChain(HttpSecurity http, ObjectMapper mapper)
            throws Exception {
        ResourceServerSecurity.applyDefaults(http, mapper)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ResourceServerSecurity.PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("referboard.security.jwt." + name + " must be set");
        }
        return value;
    }
}
