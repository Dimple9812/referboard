package com.referboard.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "referboard.security.jwt")
public record JwtProperties(String jwkSetUri, String issuer, String audience) {
}
