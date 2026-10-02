package io.github.tis199.gamecraft.paper.platform;

import io.github.tis199.gamecraft.api.GameLocation;
import io.github.tis199.gamecraft.api.GameScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Uses Paper's unified schedulers, which also route work correctly on Folia. */
public final class PaperScheduler implements GameScheduler {
    private final Plugin plugin;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runGlobal(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    @Override
    public void runAt(GameLocation location, Runnable task) {
        World world = Bukkit.getWorld(location.worldId());
        if (world == null) {
            return;
        }
        Bukkit.getRegionScheduler().execute(plugin,
                new org.bukkit.Location(world, location.x(), location.y(), location.z()), task);
    }

    @Override
    public void runForEntity(UUID entityId, Runnable task, Runnable retired) {
        runGlobal(() -> {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                retired.run();
                return;
            }
            entity.getScheduler().execute(plugin, task, retired, 1L);
        });
    }
}
