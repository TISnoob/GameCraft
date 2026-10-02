package io.github.tis199.gamecraft.paper.platform;

import io.github.tis199.gamecraft.api.AiService;
import io.github.tis199.gamecraft.api.FurnitureService;
import io.github.tis199.gamecraft.api.GameCraftServices;
import io.github.tis199.gamecraft.api.GameScheduler;
import io.github.tis199.gamecraft.api.MenuService;
import io.github.tis199.gamecraft.api.StorageService;

public record PaperGameCraftServices(
        GameScheduler scheduler,
        MenuService menus,
        FurnitureService furniture,
        StorageService storage,
        AiService ai) implements GameCraftServices {
}
