package io.github.tis199.gamecraft.client.model;

import com.google.gson.JsonObject;

import java.util.UUID;

public record FurnitureState(UUID id, String type, String game, String world,
                             double x, double y, double z, int width, int depth) {
    public static FurnitureState parse(JsonObject item) {
        return new FurnitureState(UUID.fromString(item.get("id").getAsString()),
                item.get("type").getAsString(), item.get("game").getAsString(),
                item.get("world").getAsString(), item.get("x").getAsDouble(),
                item.get("y").getAsDouble(), item.get("z").getAsDouble(),
                item.get("width").getAsInt(), item.get("depth").getAsInt());
    }
}
