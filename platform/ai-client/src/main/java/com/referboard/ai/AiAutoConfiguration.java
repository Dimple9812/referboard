package com.referboard.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.ai.internal.AnthropicLlmClient;
import com.referboard.ai.internal.DisabledLlmClient;
import com.referboard.ai.internal.HashingEmbeddingClient;
import com.referboard.ai.internal.OpenAiEmbeddingClient;
import com.referboard.ai.internal.OpenAiLlmClient;
import com.referboard.ai.internal.RetryingCaller;
import com.referboard.skills.SkillTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Locale;

/**
 * Chooses providers from {@code referboard.ai.llm.provider} / {@code referboard.ai.embedding.provider}:
 * {@code anthropic | openai | none} and {@code openai | hashing}, or {@code auto} (by which API key is set).
 */
@AutoConfiguration
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SkillTaxonomy skillTaxonomy() {
        return SkillTaxonomy.defaultTaxonomy();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmClient llmClient(AiProperties props, ObjectMapper mapper) {
        AiProperties.Llm llm = props.llm();
        String provider = llm.provider().toLowerCase(Locale.ROOT);
        if ("auto".equals(provider)) {
            provider = llm.anthropic().hasKey() ? "anthropic" : llm.openai().hasKey() ? "openai" : "none";
        }
        RetryingCaller retry = new RetryingCaller(llm.maxAttempts());
        LlmClient client = switch (provider) {
            case "anthropic" -> new AnthropicLlmClient(builder(llm), mapper, require(llm.anthropic(), "ANTHROPIC_API_KEY"),
                    orDefault(llm.anthropic().model(), "claude-sonnet-5-5"), llm.anthropic().baseUrl(), retry);
            case "openai" -> new OpenAiLlmClient(builder(llm), mapper, require(llm.openai(), "OPENAI_API_KEY"),
                    orDefault(llm.openai().model(), "gpt-4.1-mini"), llm.openai().baseUrl(), retry);
            case "none" -> new DisabledLlmClient();
            default -> throw new IllegalStateException("Unknown referboard.ai.llm.provider: " + provider);
        };
        log.info("LLM provider: {}", client.modelId());
        return client;
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingClient embeddingClient(AiProperties props, ObjectMapper mapper, SkillTaxonomy skills) {
        AiProperties.Embedding cfg = props.embedding();
        String apiKey = cfg.apiKey() != null && !cfg.apiKey().isBlank() ? cfg.apiKey() : props.llm().openai().apiKey();
        String provider = cfg.provider().toLowerCase(Locale.ROOT);
        if ("auto".equals(provider)) {
            provider = apiKey != null && !apiKey.isBlank() ? "openai" : "hashing";
        }
        EmbeddingClient client = switch (provider) {
            case "openai" -> {
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException("Embedding provider 'openai' needs OPENAI_API_KEY");
                }
                yield new OpenAiEmbeddingClient(builder(props.llm()), mapper, apiKey, cfg.model(), cfg.dimensions(),
                        cfg.baseUrl(), new RetryingCaller(props.llm().maxAttempts()));
            }
            case "hashing" -> new HashingEmbeddingClient(cfg.dimensions(), skills);
            default -> throw new IllegalStateException("Unknown referboard.ai.embedding.provider: " + provider);
        };
        log.info("Embedding model: {}", client.model());
        return client;
    }

    private static RestClient.Builder builder(AiProperties.Llm llm) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(llm.timeout());
        return RestClient.builder().requestFactory(factory);
    }

    private static String require(AiProperties.Provider provider, String envName) {
        if (!provider.hasKey()) {
            throw new IllegalStateException("LLM provider selected but " + envName + " is not set");
        }
        return provider.apiKey();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
