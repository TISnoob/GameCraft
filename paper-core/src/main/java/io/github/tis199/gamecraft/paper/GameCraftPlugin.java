package io.github.tis199.gamecraft.paper;

import io.github.tis199.gamecraft.api.GameCraftServices;
import io.github.tis199.gamecraft.paper.ai.AiServiceImpl;
import io.github.tis199.gamecraft.paper.command.GameCraftCommand;
import io.github.tis199.gamecraft.paper.furniture.FurnitureManager;
import io.github.tis199.gamecraft.paper.integration.PlaceholderIntegration;
import io.github.tis199.gamecraft.paper.menu.InventoryMenuService;
import io.github.tis199.gamecraft.paper.module.GameModuleManager;
import io.github.tis199.gamecraft.paper.platform.PaperGameCraftServices;
import io.github.tis199.gamecraft.paper.platform.PaperScheduler;
import io.github.tis199.gamecraft.paper.storage.JdbcStorageService;
import io.github.tis199.gamecraft.paper.session.GameSessionManagerImpl;
import io.github.tis199.gamecraft.paper.session.GameRoomManager;
import io.github.tis199.gamecraft.paper.session.GameBoardServiceImpl;
import io.github.tis199.gamecraft.paper.resourcepack.ResourcePackService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameCraftPlugin extends JavaPlugin {
    private ExecutorService ioExecutor;
    private JdbcStorageService storage;
    private AiServiceImpl aiService;
    private GameModuleManager moduleManager;
    private FurnitureManager furniture;
    private GameSessionManagerImpl sessionManager;
    private InventoryMenuService menuService;
    private GameRoomManager roomManager;
    private GameBoardServiceImpl boardService;
    private ResourcePackService resourcePackService;
    private PaperGameCraftServices services;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        ioExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "GameCraft-IO");
            thread.setDaemon(true);
            return thread;
        });

        try {
            storage = new JdbcStorageService(this, ioExecutor);
            storage.initialize();
        } catch (Exception exception) {
            getLogger().severe("Could not initialize GameCraft storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        aiService = new AiServiceImpl(this, ioExecutor);
        PaperScheduler scheduler = new PaperScheduler(this);
        sessionManager = new GameSessionManagerImpl(this, scheduler);
        boardService = new GameBoardServiceImpl(this, sessionManager, scheduler);
        furniture = new FurnitureManager(this, scheduler, sessionManager);
        menuService = new InventoryMenuService(this, scheduler);
        services = new PaperGameCraftServices(scheduler, menuService, furniture, storage, aiService, sessionManager, boardService);
        roomManager = new GameRoomManager(sessionManager, furniture);
        resourcePackService = new ResourcePackService(this);

        GameCraftCommand command = new GameCraftCommand(this, furniture, sessionManager, menuService, roomManager);
        if (getCommand("gamecraft") != null) {
            getCommand("gamecraft").setExecutor(command);
            getCommand("gamecraft").setTabCompleter(command);
        }

        moduleManager = new GameModuleManager(this, services, ioExecutor);
        moduleManager.loadCachedModules();
        moduleManager.checkRegistryAsync();

        // Game actions now arrive over gamecraft:client from the required client mod.

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PlaceholderIntegration(this).register();
            getLogger().info("PlaceholderAPI integration enabled.");
        }

        getLogger().info("GameCraft core enabled. Install game modules separately and enable them in config.yml.");
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            sessionManager.close();
        }
        if (resourcePackService != null) {
            resourcePackService.close();
        }
        if (moduleManager != null) {
            moduleManager.close();
        }
        if (furniture != null) {
            furniture.close();
        }
        if (storage != null) {
            storage.close();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
    }

    public void reloadGameCraft() {
        reloadConfig();
        if (moduleManager != null) {
            moduleManager.checkRegistryAsync();
        }
        getLogger().info("Configuration reloaded. Module code changes require a server restart.");
    }

    public GameModuleManager moduleManager() {
        return moduleManager;
    }

    public FurnitureManager furniture() {
        return furniture;
    }

    public GameCraftServices services() {
        return services;
    }

    public AiServiceImpl aiService() {
        return aiService;
    }

    public GameBoardServiceImpl boardService() {
        return boardService;
    }
}
