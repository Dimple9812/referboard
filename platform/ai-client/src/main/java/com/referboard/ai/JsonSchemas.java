package com.referboard.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/** Loads JSON Schemas shipped as classpath resources (e.g. {@code prompts/resume-schema.json}). */
public final class JsonSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemas() {
    }

    public static JsonNode load(String resource) {
        try (InputStream in = JsonSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Schema not found on classpath: " + resource);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid JSON schema " + resource, e);
        }
    }

    public static String loadText(String resource) {
        try (InputStream in = JsonSchemas.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
