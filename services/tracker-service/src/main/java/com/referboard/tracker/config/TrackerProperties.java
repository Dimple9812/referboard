package com.referboard.tracker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "referboard.tracker")
public record TrackerProperties(@DefaultValue("2000") int maxApplications) {
}
