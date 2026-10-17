package com.referboard.billing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** A JWT for everything except the price list and Razorpay's webhook (authorised by its HMAC signature). */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        ResourceServerSecurity.applyDefaults(http, mapper)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ResourceServerSecurity.PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/billing/plans").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/billing/webhooks/razorpay").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
