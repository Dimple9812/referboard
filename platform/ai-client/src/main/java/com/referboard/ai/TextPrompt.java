package com.referboard.ai;

public record TextPrompt(String system, String user, int maxTokens, double temperature) {

    public TextPrompt {
        if (maxTokens <= 0) {
            maxTokens = 1024;
        }
    }

    public static TextPrompt of(String system, String user) {
        return new TextPrompt(system, user, 1024, 0.7);
    }
}
