package com.referboard.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A request for schema-constrained JSON output.
 *
 * @param toolName  name of the function/tool the model is forced to call; also the OpenAI schema name
 * @param schema    JSON Schema of the expected object (see {@link JsonSchemas})
 * @param type      record/class the JSON is mapped into
 */
public record StructuredPrompt<T>(
        String system,
        String user,
        String toolName,
        String toolDescription,
        JsonNode schema,
        Class<T> type,
        int maxTokens,
        double temperature
) {
    public StructuredPrompt {
        if (maxTokens <= 0) {
            maxTokens = 4096;
        }
    }

    public static <T> StructuredPrompt<T> of(String system, String user, String toolName, String toolDescription,
                                             JsonNode schema, Class<T> type) {
        return new StructuredPrompt<>(system, user, toolName, toolDescription, schema, type, 4096, 0.0);
    }
}
