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

/**
 * Claude via the Messages API. Structured output uses a single tool with {@code tool_choice} forced to it, so the
 * reply is a {@code tool_use} block whose {@code input} conforms to the schema.
 */
public class AnthropicLlmClient implements LlmClient {

    static final String API_VERSION = "2023-06-01";

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final RetryingCaller retry;

    public AnthropicLlmClient(RestClient.Builder builder, ObjectMapper mapper, String apiKey, String model,
                              String baseUrl, RetryingCaller retry) {
        this.http = builder
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", API_VERSION)
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
        return "anthropic:" + model;
    }

    @Override
    public <T> T structured(StructuredPrompt<T> prompt) {
        ObjectNode body = baseBody(prompt.system(), prompt.user(), prompt.maxTokens(), prompt.temperature());
        ObjectNode tool = body.putArray("tools").addObject();
        tool.put("name", prompt.toolName());
        tool.put("description", prompt.toolDescription());
        tool.set("input_schema", prompt.schema());
        ObjectNode choice = body.putObject("tool_choice");
        choice.put("type", "tool");
        choice.put("name", prompt.toolName());

        JsonNode response = post(body);
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && prompt.toolName().equals(block.path("name").asText())) {
                try {
                    return mapper.treeToValue(block.path("input"), prompt.type());
                } catch (Exception e) {
                    throw new LlmCallException("Claude output did not match " + prompt.type().getSimpleName(), e);
                }
            }
        }
        throw new LlmCallException("Claude returned no tool_use block (stop_reason="
                + response.path("stop_reason").asText() + ")");
    }

    @Override
    public String text(TextPrompt prompt) {
        JsonNode response = post(baseBody(prompt.system(), prompt.user(), prompt.maxTokens(), prompt.temperature()));
        StringBuilder out = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                out.append(block.path("text").asText());
            }
        }
        if (out.isEmpty()) {
            throw new LlmCallException("Claude returned no text");
        }
        return out.toString().trim();
    }

    private ObjectNode baseBody(String system, String user, int maxTokens, double temperature) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (system != null && !system.isBlank()) {
            body.put("system", system);
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", user);
        return body;
    }

    private JsonNode post(ObjectNode body) {
        return retry.call("Anthropic", () -> http.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }
}
