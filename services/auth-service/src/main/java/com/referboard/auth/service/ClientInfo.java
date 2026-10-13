package com.referboard.auth.service;

/** Where a sign-in came from; stored with the refresh token so users can review sessions later. */
public record ClientInfo(String userAgent, String ipAddress) {

    public static final ClientInfo UNKNOWN = new ClientInfo(null, null);
}
