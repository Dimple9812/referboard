package com.referboard.ai;

/** A configured provider failed (after retries) or returned output that doesn't fit the schema. */
public class LlmCallException extends RuntimeException {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
