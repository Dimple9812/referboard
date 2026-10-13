package com.referboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Published by auth-service when a new account is created (password or Google). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserRegisteredEvent(
        EventMetadata metadata,
        UUID userId,
        String email,
        String fullName,
        String authProvider
) implements DomainEvent {

    @Override
    public String topic() {
        return Topics.USER_REGISTERED;
    }

    @Override
    public String key() {
        return userId.toString();
    }
}
