package io.github.tis199.gamecraft.api;

import java.util.UUID;

/** Public furniture operations. Calls that touch the world are region scheduled. */
public interface FurnitureService {
    UUID placeTable(GameLocation location, int width, int depth);

    UUID placeChair(GameLocation location);

    boolean remove(UUID furnitureId);

    boolean sit(UUID playerId, UUID chairId);

    boolean stand(UUID playerId);
}
