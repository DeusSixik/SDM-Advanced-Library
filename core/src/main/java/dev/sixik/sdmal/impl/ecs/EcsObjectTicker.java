package dev.sixik.sdmal.impl.ecs;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.ecs.EcsObjectException;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;

/**
 * Dispatches world-driven ECS ticks with pause handling and failure reporting.
 */
public final class EcsObjectTicker {

    /**
     * Prevents construction of this stateless implementation helper.
     */
    private EcsObjectTicker() {
    }

    /**
     * Opens PRE before vanilla entity/world simulation when the world's simulation is running.
     *
     * @param manager this world's manager
     * @param running whether vanilla permits simulation
     */
    public static void begin(EcsObjectManager manager, boolean running) {
        if (running && !manager.isClosed()) manager.beginTick();
    }

    /**
     * Runs NORMAL after entity processing and before block-entity dispatch.
     *
     * @param manager this world's manager
     */
    public static void normal(EcsObjectManager manager) {
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
    public static void tick(EcsObjectManager manager, boolean running) {
        if ((!running || manager.isClosed()) && !manager.isTicking()) {
            return;
        }
        try {
            if (manager.isTicking()) manager.endTick();
            else manager.tickAll();
        } catch (EcsObjectException failure) {
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
