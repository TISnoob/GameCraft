package io.github.tis199.gamecraft.api;

import java.util.UUID;

/** Public furniture operations. Calls that touch the world are region scheduled. */
public interface FurnitureService {
    UUID placeTable(GameLocation location, int width, int depth);

    /** Places a modelled table using the footprint and appearance declared for a game module. */
    default UUID placeGameTable(String gameId, GameLocation location) {
        throw new UnsupportedOperationException("Per-game tables are not supported by this furniture provider");
    }

    UUID placeChair(GameLocation location);

    /** Places a chair with the selected game's pack model. */
    default UUID placeGameChair(String gameId, GameLocation location) {
        return placeChair(location);
    }

    boolean remove(UUID furnitureId);

    boolean sit(UUID playerId, UUID chairId);

    boolean stand(UUID playerId);
}
