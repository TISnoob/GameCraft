package io.github.tis199.gamecraft.api;

import java.util.List;
import java.util.Objects;

public record MenuDefinition(String id, String title, List<MenuOption> options) {
    public MenuDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        options = List.copyOf(options);
        if (id.isBlank() || title.isBlank()) {
            throw new IllegalArgumentException("Menu id and title must not be blank");
        }
    }
}
