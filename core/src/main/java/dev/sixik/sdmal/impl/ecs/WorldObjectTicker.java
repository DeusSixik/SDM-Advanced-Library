package dev.sixik.sdmal.impl.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.ecs.WorldObjectException;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;

/**
 * Dispatches world-driven ECS ticks with pause handling and failure reporting.
 */
public final class WorldObjectTicker {

    /**
     * Prevents construction of this stateless implementation helper.
     */
    private WorldObjectTicker() {
    }

    /**
     * Opens PRE before vanilla entity/world simulation when the world's simulation is running.
     *
     * @param manager this world's manager
     * @param running whether vanilla permits simulation
     */
    public static void begin(WorldObjectManager manager, boolean running) {
        if (running && !manager.isClosed()) manager.beginTick();
    }

    /**
     * Runs NORMAL after entity processing and before block-entity dispatch.
     *
     * @param manager this world's manager
     */
    public static void normal(WorldObjectManager manager) {
        if (manager.isTicking() && !manager.isClosed()) manager.tickNormal();
    }

    /**
     * Ticks one world's live manager while its simulation is running.
     *
     * <p>The caller is the world's tick thread. Components receive the manager's
     * own sequence rather than world time. Aggregated component failures are
     * logged after every object has been attempted, allowing later ticks to run.
     *
     * @param manager manager belonging to exactly this world instance, non-null
     * @param running whether vanilla permits simulation during this world tick
     */
    public static void tick(WorldObjectManager manager, boolean running) {
        if ((!running || manager.isClosed()) && !manager.isTicking()) {
            return;
        }
        try {
            if (manager.isTicking()) manager.endTick();
            else manager.tickAll();
        } catch (WorldObjectException failure) {
            if (shouldReport(manager.consecutiveTickFailures()))
                SDMAdvancedLibrary.LOGGER.error("ECS world tick failed at manager tick " + manager.lastTick()
                        + " (consecutive failed ticks: " + manager.consecutiveTickFailures() + ")", failure);
        }
    }

    /**
     * Limits repeated stack traces while preserving the first failure after recovery.
     *
     * @param failures consecutive failed manager ticks, greater than zero
     * @return true for the first failure and every hundredth consecutive failure
     */
    public static boolean shouldReport(long failures) {
        return failures > 0 && (failures == 1 || failures % 100 == 0);
    }
}
