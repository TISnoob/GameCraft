package io.github.tis199.gamecraft.api;

import java.util.Objects;

public record AiRequest(String systemPrompt, String prompt, double temperature, int maxOutputTokens) {
    public AiRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(prompt, "prompt");
        if (temperature < 0 || temperature > 2 || maxOutputTokens < 1) {
            throw new IllegalArgumentException("Invalid AI generation settings");
        }
    }
}
