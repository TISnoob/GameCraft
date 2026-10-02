package io.github.tis199.gamecraft.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable session snapshot passed to renderer hooks. */
public record GameSessionView(UUID sessionId, String state, List<UUID> players, Map<String, String> properties) {
    public GameSessionView {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        players = List.copyOf(players);
        properties = Map.copyOf(properties);
    }
}
