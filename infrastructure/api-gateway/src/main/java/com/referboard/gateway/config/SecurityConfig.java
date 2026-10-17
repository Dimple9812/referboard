package com.referboard.gateway.config;

import com.referboard.gateway.web.ApiErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * JWTs are validated at the edge (signature via auth-service's JWKS, issuer, audience, expiry), so an invalid token
 * never reaches a service; services validate again (defence in depth). The token is passed through unchanged.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Reachable without a token. Everything else under /api requires one. */
    static final String[] PUBLIC_POST = {"/api/auth/signup", "/api/auth/login", "/api/auth/google",
            "/api/auth/refresh", "/api/auth/logout", "/api/auth/extension/refresh", "/api/auth/extension/disconnect",
            "/api/billing/webhooks/razorpay"};
    static final String[] PUBLIC_ANY = {"/api/notifications/unsubscribe", "/api/billing/plans", "/.well-known/jwks.json",
            "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            "/webjars/**"};

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerAuthenticationEntryPoint unauthorized = (exchange, e) -> ApiErrors.write(exchange,
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue (missing or invalid token).");
        ServerAccessDeniedHandler denied = (exchange, e) -> ApiErrors.write(exchange, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "You don't have access to this.");
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)   // stateless bearer tokens; refresh cookie is SameSite=Strict
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(c -> { })
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .pathMatchers(PUBLIC_ANY).permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll())   // non-API paths: nothing is routed, so they 404
                .oauth2ResourceServer(o -> o.jwt(j -> { }).authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(denied))
                .build();
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties jwt) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwt.jwkSetUri()).build();
        decoder.setJwtValidator(validator(jwt.issuer(), jwt.audience()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceCheck = token -> token.getAudience() != null
                && token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceCheck);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(java.util.stream.Stream.concat(props.corsAllowedOrigins().stream(),
                        props.extensionOrigins().stream())
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition", "Retry-After",
                "X-RateLimit-Remaining"));
        cors.setAllowCredentials(true);   // the refresh-token cookie
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
