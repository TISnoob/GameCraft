package io.github.tis199.gamecraft.api;

import java.util.Objects;
import java.util.UUID;

public record GameLocation(UUID worldId, double x, double y, double z) {
    public GameLocation {
        Objects.requireNonNull(worldId, "worldId");
    }
}
