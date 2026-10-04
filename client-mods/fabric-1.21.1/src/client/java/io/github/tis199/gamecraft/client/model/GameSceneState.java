package io.github.tis199.gamecraft.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record GameSceneState(UUID sessionId, String game, String world,
                             double x, double y, double z, String title, String actionType,
                             List<GameOption> options) {
    public static GameSceneState parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray array = root.getAsJsonArray("options");
        List<GameOption> options = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonObject entry = array.get(index).getAsJsonObject();
            List<String> descriptions = new ArrayList<>();
            JsonArray lines = entry.getAsJsonArray("description");
            for (int line = 0; line < lines.size(); line++) descriptions.add(lines.get(line).getAsString());
            options.add(new GameOption(entry.get("id").getAsString(), entry.get("title").getAsString(), descriptions));
        }
        return new GameSceneState(UUID.fromString(root.get("session").getAsString()),
                root.get("game").getAsString(), root.get("world").getAsString(),
                root.get("x").getAsDouble(), root.get("y").getAsDouble(), root.get("z").getAsDouble(),
                root.get("title").getAsString(), root.get("actionType").getAsString(), List.copyOf(options));
    }

    public GameOption option(String id) {
        return options.stream().filter(option -> option.id().equals(id)).findFirst().orElse(null);
    }

    public GameOption optionStarting(String prefix) {
        return options.stream().filter(option -> option.id().startsWith(prefix)).findFirst().orElse(null);
    }
}
