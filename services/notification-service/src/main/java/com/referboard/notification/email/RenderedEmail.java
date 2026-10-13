package com.referboard.notification.email;

/** A fully rendered email: HTML and plain-text alternatives, plus the one-click unsubscribe URL if any. */
public record RenderedEmail(String subject, String html, String text, String unsubscribeUrl) {
}
