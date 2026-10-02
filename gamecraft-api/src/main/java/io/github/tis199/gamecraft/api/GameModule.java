package io.github.tis199.gamecraft.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Entry point implemented by a downloadable GameCraft module.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}.
 * Module code is activated only after its jar has passed manifest and checksum
 * validation. Game rules should remain independent of Bukkit and proxy APIs.</p>
 */
public interface GameModule {
    GameModuleDescriptor descriptor();

    default void onLoad(GameModuleContext context) throws Exception {
    }

    default void onEnable() throws Exception {
    }

    default void onDisable() {
    }

    /** Optional main menu to display when this module is selected. */
    default Optional<MenuDefinition> menu(UUID playerId) {
        return Optional.empty();
    }

    /** Receives a normalized action, regardless of whether it came from an inventory or a Bedrock form. */
    default void onPlayerAction(GameAction action) {
    }

    /** Supplies a platform-neutral view model for a future game renderer. */
    default Optional<GameRenderView> render(GameSessionView session) {
        return Optional.empty();
    }
}
