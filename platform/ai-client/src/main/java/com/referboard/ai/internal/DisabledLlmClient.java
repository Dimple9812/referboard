package com.referboard.ai.internal;

import com.referboard.ai.LlmClient;
import com.referboard.ai.LlmUnavailableException;
import com.referboard.ai.StructuredPrompt;
import com.referboard.ai.TextPrompt;

/** Used when no API key is configured; every call signals callers to use their offline fallback. */
public class DisabledLlmClient implements LlmClient {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String modelId() {
        return "none";
    }

    @Override
    public <T> T structured(StructuredPrompt<T> prompt) {
        throw new LlmUnavailableException();
    }

    @Override
    public String text(TextPrompt prompt) {
        throw new LlmUnavailableException();
    }
}
