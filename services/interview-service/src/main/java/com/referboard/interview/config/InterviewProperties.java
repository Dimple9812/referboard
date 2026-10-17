package com.referboard.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "referboard.interview")
public record InterviewProperties(@DefaultValue("3") int freePacksPerMonth) {
}
