package io.github.tis199.gamecraft.api;

import java.util.UUID;
import java.util.function.Consumer;

/** Renders a common menu model as a Java inventory or a Bedrock form. */
public interface MenuService {
    void open(UUID playerId, MenuDefinition menu, Consumer<String> selectedOption);
}
