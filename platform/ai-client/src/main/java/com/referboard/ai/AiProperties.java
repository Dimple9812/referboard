package com.referboard.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code referboard.ai.*}. Providers default to {@code auto}: use whichever API key is present, otherwise fall back
 * to offline implementations (heuristic parsing / hashing embeddings) so the platform runs without any keys.
 */
@ConfigurationProperties(prefix = "referboard.ai")
public record AiProperties(
        @DefaultValue Llm llm,
        @DefaultValue Embedding embedding
) {

    public record Llm(
            @DefaultValue("auto") String provider,
            @DefaultValue Provider anthropic,
            @DefaultValue Provider openai,
            @DefaultValue("PT60S") Duration timeout,
            @DefaultValue("3") int maxAttempts
    ) {
    }

    public record Provider(
            String apiKey,
            String model,
            String baseUrl
    ) {
        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Embedding(
            @DefaultValue("auto") String provider,
            @DefaultValue("text-embedding-3-small") String model,
            @DefaultValue("1536") int dimensions,
            String apiKey,
            String baseUrl
    ) {
    }
}
