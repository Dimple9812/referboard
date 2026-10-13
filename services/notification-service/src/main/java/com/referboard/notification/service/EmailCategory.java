package com.referboard.notification.service;

import java.util.Arrays;
import java.util.Optional;

/** Email categories a user can switch off separately (in settings or by the unsubscribe link). */
public enum EmailCategory {
    REMINDERS("reminders", "referral reminders"),
    DIGEST("digest", "the daily match digest");

    private final String param;
    private final String label;

    EmailCategory(String param, String label) {
        this.param = param;
        this.label = label;
    }

    public String param() {
        return param;
    }

    public String label() {
        return label;
    }

    public static Optional<EmailCategory> fromParam(String p) {
        return Arrays.stream(values()).filter(c -> c.param.equalsIgnoreCase(p)).findFirst();
    }
}
