package com.referboard.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.common.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Everything needs a JWT except the unsubscribe link in emails, which is authorised by its secret token. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        ResourceServerSecurity.applyDefaults(http, mapper)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ResourceServerSecurity.PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/notifications/unsubscribe").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
