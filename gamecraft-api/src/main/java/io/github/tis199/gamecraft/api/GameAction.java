package io.github.tis199.gamecraft.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A validated, platform-neutral player action delivered to a module. */
public record GameAction(String type, UUID playerId, Map<String, String> data) {
    public GameAction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(playerId, "playerId");
        data = Map.copyOf(data);
        if (type.isBlank()) {
            throw new IllegalArgumentException("Action type must not be blank");
        }
    }
}
