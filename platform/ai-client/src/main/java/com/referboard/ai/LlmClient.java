package com.referboard.ai;

/**
 * Provider-neutral LLM access. Callers must treat {@link LlmUnavailableException} as "no LLM configured" and use
 * their deterministic fallback; {@link LlmCallException} means a configured provider failed after retries.
 *
 * <p>Prompts that embed user documents (resumes, job descriptions) must tell the model that the document is data,
 * not instructions; the structured-output schema then constrains what can come back.
 */
public interface LlmClient {

    /** False when no provider is configured (offline/dev mode). */
    boolean available();

    /** Identifier stored alongside outputs for auditing, e.g. {@code anthropic:claude-sonnet-5-5}. */
    String modelId();

    /** Calls the model with a JSON schema it must fill, and maps the result to {@code prompt.type()}. */
    <T> T structured(StructuredPrompt<T> prompt);

    /** Plain text completion (used for drafting messages). */
    String text(TextPrompt prompt);
}
