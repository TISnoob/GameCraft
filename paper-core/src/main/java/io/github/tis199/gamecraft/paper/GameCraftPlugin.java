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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GameCraftPlugin extends JavaPlugin {
    private ExecutorService ioExecutor;
    private JdbcStorageService storage;
    private AiServiceImpl aiService;
    private GameModuleManager moduleManager;
    private FurnitureManager furniture;
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
        furniture = new FurnitureManager(this);
        InventoryMenuService menuService = new InventoryMenuService(this);
        PaperScheduler scheduler = new PaperScheduler(this);
        services = new PaperGameCraftServices(scheduler, menuService, furniture, storage, aiService);

        GameCraftCommand command = new GameCraftCommand(this, menuService, furniture);
        if (getCommand("gamecraft") != null) {
            getCommand("gamecraft").setExecutor(command);
            getCommand("gamecraft").setTabCompleter(command);
        }

        moduleManager = new GameModuleManager(this, services, ioExecutor);
        moduleManager.loadCachedModules();
        moduleManager.checkRegistryAsync();

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PlaceholderIntegration(this).register();
            getLogger().info("PlaceholderAPI integration enabled.");
        }

        getLogger().info("GameCraft core enabled. No game modules are bundled with this release.");
    }

    @Override
    public void onDisable() {
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
}
