package io.github.tis199.gamecraft.api;

import java.util.UUID;

/** Scheduling boundary that keeps game modules independent of Paper/Folia APIs. */
public interface GameScheduler {
    void runGlobal(Runnable task);

    void runAsync(Runnable task);

    void runAt(GameLocation location, Runnable task);

    void runForEntity(UUID entityId, Runnable task, Runnable retired);
}
