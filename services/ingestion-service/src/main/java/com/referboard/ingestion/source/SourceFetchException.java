package com.referboard.ingestion.source;

/** Fetch failure classified so the orchestrator can set the source's status. */
public class SourceFetchException extends RuntimeException {

    public enum Kind {
        /** 404: board doesn't exist (wrong/renamed slug). Not retried until retry-not-found-after. */
        NOT_FOUND,
        /** robots.txt disallows this path for our user agent. */
        BLOCKED_BY_ROBOTS,
        /** Network error, 429 or 5xx after retries. */
        TRANSIENT,
        /** Anything else (unexpected 4xx, unparseable body). */
        FAILED
    }

    private final Kind kind;

    public SourceFetchException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public SourceFetchException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
