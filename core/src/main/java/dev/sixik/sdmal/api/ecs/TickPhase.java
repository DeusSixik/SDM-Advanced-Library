package dev.sixik.sdmal.api.ecs;

/**
 * Ordered world-tick boundaries shared by all Minecraft implementations.
 */
public enum TickPhase {
    /**
     * Before vanilla world/entity simulation.
     */
    PRE,
    /**
     * After entity simulation and before block-entity ticks.
     */
    NORMAL,
    /**
     * At the end of world simulation.
     */
    POST
}
