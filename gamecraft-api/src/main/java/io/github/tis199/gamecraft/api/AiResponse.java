package io.github.tis199.gamecraft.api;

import java.util.Objects;

public record AiResponse(String provider, String model, String text) {
    public AiResponse {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(text, "text");
    }
}
