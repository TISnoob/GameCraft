package io.github.tis199.gamecraft.api;

import java.util.Map;
import java.util.Objects;

/** Renderer-neutral description of a board or game surface. */
public record GameRenderView(String rendererId, Map<String, String> properties) {
    public GameRenderView {
        Objects.requireNonNull(rendererId, "rendererId");
        properties = Map.copyOf(properties);
        if (rendererId.isBlank()) {
            throw new IllegalArgumentException("Renderer id must not be blank");
        }
    }
}
