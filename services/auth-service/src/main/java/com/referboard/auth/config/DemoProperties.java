package com.referboard.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One-click demo sign-in for a public showcase deployment. {@code accountEmail} empty (the default) turns it off.
 * The account must already exist (deploy/seed-demo.sh creates and fills it) and must not be an admin.
 */
@ConfigurationProperties(prefix = "referboard.demo")
public record DemoProperties(String accountEmail) {

    public boolean enabled() {
        return accountEmail != null && !accountEmail.isBlank();
    }
}
