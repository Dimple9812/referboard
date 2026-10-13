package com.referboard.ai;

/** No LLM provider is configured. Callers fall back to deterministic logic. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException() {
        super("No LLM provider configured (set ANTHROPIC_API_KEY or OPENAI_API_KEY)");
    }
}
