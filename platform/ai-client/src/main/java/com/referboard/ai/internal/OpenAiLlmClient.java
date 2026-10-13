package com.referboard.ai.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.referboard.ai.LlmCallException;
import com.referboard.ai.LlmClient;
import com.referboard.ai.StructuredPrompt;
import com.referboard.ai.TextPrompt;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** OpenAI Chat Completions; structured output via {@code response_format: json_schema}. */
public class OpenAiLlmClient implements LlmClient {

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final RetryingCaller retry;

    public OpenAiLlmClient(RestClient.Builder builder, ObjectMapper mapper, String apiKey, String model,
                           String baseUrl, RetryingCaller retry) {
        this.http = builder
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.mapper = mapper;
        this.model = model;
        this.retry = retry;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String modelId() {
        return "openai:" + model;
    }

    @Override
    public <T> T structured(StructuredPrompt<T> prompt) {
        ObjectNode body = baseBody(prompt.system(), prompt.user(), prompt.maxTokens(), prompt.temperature());
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", prompt.toolName());
        jsonSchema.put("description", prompt.toolDescription());
        jsonSchema.set("schema", prompt.schema());
        jsonSchema.put("strict", false);

        String content = firstMessage(post(body));
        try {
            return mapper.readValue(content, prompt.type());
        } catch (Exception e) {
            throw new LlmCallException("OpenAI output did not match " + prompt.type().getSimpleName(), e);
        }
    }

    @Override
    public String text(TextPrompt prompt) {
        return firstMessage(post(baseBody(prompt.system(), prompt.user(), prompt.maxTokens(), prompt.temperature())))
                .trim();
    }

    private ObjectNode baseBody(String system, String user, int maxTokens, double temperature) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_completion_tokens", maxTokens);
        body.put("temperature", temperature);
        ArrayNode messages = body.putArray("messages");
        if (system != null && !system.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
        }
        ObjectNode u = messages.addObject();
        u.put("role", "user");
        u.put("content", user);
        return body;
    }

    private String firstMessage(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new LlmCallException("OpenAI returned no content (finish_reason="
                    + response.path("choices").path(0).path("finish_reason").asText() + ")");
        }
        return content.asText();
    }

    private JsonNode post(ObjectNode body) {
        return retry.call("OpenAI", () -> http.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }
}
