package dev.sixik.sdmal.example.ecs.multiblock;

/**
 * Transient formation state; restored controllers must inspect the current world again.
 */
public enum MultiblockStatus {
    /**
     * Structure has not been inspected since the controller was bound.
     */
    CHECKING,
    /**
     * All eight iron supports and the redstone core are present and loaded.
     */
    FORMED,
    /**
     * At least one required position contains the wrong block.
     */
    INCOMPLETE,
    /**
     * A required chunk is absent; inspection does not load it.
     */
    UNLOADED,
    /**
     * A required position lies outside build height or the world border.
     */
    OUT_OF_BOUNDS
}
