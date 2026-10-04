package io.github.tis199.gamecraft.api;

/** Platform-neutral services available to game modules. */
public interface GameCraftServices {
    GameScheduler scheduler();

    MenuService menus();

    FurnitureService furniture();

    StorageService storage();

    AiService ai();

    GameSessionManager sessions();

    GameBoardService boards();
}
