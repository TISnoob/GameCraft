package io.github.tis199.gamecraft.api;

import java.util.List;
import java.util.Objects;

public record MenuOption(String id, String title, List<String> description) {
    public MenuOption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        description = List.copyOf(description);
        if (id.isBlank() || title.isBlank()) {
            throw new IllegalArgumentException("Menu option id and title must not be blank");
        }
    }
}
