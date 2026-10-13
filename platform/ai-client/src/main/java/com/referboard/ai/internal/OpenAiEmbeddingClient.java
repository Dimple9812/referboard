package com.referboard.ai.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.referboard.ai.EmbeddingClient;
import com.referboard.ai.LlmCallException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** OpenAI embeddings (default {@code text-embedding-3-small}, 1536 dims), batched up to 96 inputs per call. */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final int BATCH = 96;
    private static final int MAX_CHARS = 24_000; // ~6k tokens, safely under the 8k-token input limit

    private final RestClient http;
    private final ObjectMapper mapper;
    private final String model;
    private final int dimensions;
    private final RetryingCaller retry;

    public OpenAiEmbeddingClient(RestClient.Builder builder, ObjectMapper mapper, String apiKey, String model,
                                 int dimensions, String baseUrl, RetryingCaller retry) {
        this.http = builder
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.mapper = mapper;
        this.model = model;
        this.dimensions = dimensions;
        this.retry = retry;
    }

    @Override
    public String model() {
        return "openai:" + model + ":" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> chunk = texts.subList(from, Math.min(texts.size(), from + BATCH));
            out.addAll(call(chunk));
        }
        return out;
    }

    private List<float[]> call(List<String> chunk) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("dimensions", dimensions);
        var input = body.putArray("input");
        chunk.forEach(t -> input.add(t == null || t.isBlank() ? " " : t.substring(0, Math.min(t.length(), MAX_CHARS))));

        JsonNode response = retry.call("OpenAI embeddings", () -> http.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));

        float[][] vectors = new float[chunk.size()][];
        for (JsonNode item : response.path("data")) {
            int index = item.path("index").asInt();
            JsonNode values = item.path("embedding");
            float[] v = new float[values.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) values.get(i).asDouble();
            }
            if (v.length != dimensions) {
                throw new LlmCallException("Expected " + dimensions + " dims, got " + v.length);
            }
            vectors[index] = v;
        }
        if (Arrays.stream(vectors).anyMatch(v -> v == null)) {
            throw new LlmCallException("OpenAI embeddings response missing items");
        }
        return Arrays.asList(vectors);
    }
}
