package io.github.tis199.gamecraft.api;

import java.nio.file.Path;

/** Services and per-module data folder provided by the GameCraft runtime. */
public record GameModuleContext(
        Path dataFolder,
        ModuleConfiguration configuration,
        GameCraftServices services) {
}
