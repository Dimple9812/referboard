package com.referboard.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "referboard.gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:5173") List<String> corsAllowedOrigins,
        /** chrome-extension://&lt;id&gt; origins of the ReferBoard extension (it calls the API with a bearer token). */
        @DefaultValue("") List<String> extensionOrigins,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue RateLimits rateLimits,
        Map<String, String> services
) {
    public record RateLimits(
            @DefaultValue("120") int defaultPerMinute,
            @DefaultValue("10") int authPerMinute,
            @DefaultValue("10") int expensivePerMinute
    ) {
    }

    public String service(String name) {
        String uri = services == null ? null : services.get(name);
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("No location configured for service '" + name
                    + "' (referboard.gateway.services." + name + ")");
        }
        return uri;
    }
}
