package dev.sixik.sdmal.example.ecs;

import dev.sixik.sdmal.api.ecs.EcsAccess;
import dev.sixik.sdmal.api.ecs.EcsSystem;
import dev.sixik.sdmal.api.ecs.WorldContext;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;

/**
 * Copyable snapshot-system example: totals generator charge on workers without reading live
 * components there. Optional aggregate-system example. Individual generators already tick
 * automatically through SnapshotTickableComponent; register this additional system only when a
 * world-wide summary is needed.
 */
public final class GeneratorSummarySystem implements EcsSystem<int[], Long> {
    /**
     * Read-only resources cached once rather than rebuilding the declaration every tick.
     */
    private static final EcsAccess ACCESS = EcsAccess.NONE.read(GeneratorComponent.class);

    /**
     * Last committed sum of stored charge, in generator charge units; read on the owner thread.
     */
    private long totalStored;

    /**
     * @return stable id, unique within each world's scheduler
     */
    @Override
    public String id() {
        return "sdmal_example:generator_summary";
    }

    /**
     * @return read-only generator resource declaration
     */
    @Override
    public EcsAccess access() {
        return ACCESS;
    }

    /**
     * @return true because compute only reads a detached primitive array
     */
    @Override
    public boolean parallel() {
        return true;
    }

    /**
     * @return true because a delayed statistics update is acceptable
     */
    @Override
    public boolean mayDefer() {
        return true;
    }

    /**
     * Schedules statistics once per twenty manager ticks, independent of individual generator
     * intervals.
     *
     * @param tick manager tick sequence
     * @return true on ticks 1, 21, 41, and so on
     */
    @Override
    public boolean isDue(long tick) {
        return (tick - 1) % 20 == 0;
    }

    /**
     * Copies charge values on the world thread, explicitly excluding sleeping objects. Object
     * references and mutable components are never passed into the worker snapshot.
     *
     * @param context current phase context
     * @param manager owner-thread object manager
     * @return exclusively owned primitive charge array; unused trailing entries are zero
     */
    @Override
    public int[] prepare(WorldContext context, WorldObjectManager manager) {
        var objects = manager.query(GeneratorComponent.class);
        int[] stored = new int[objects.size()];
        int count = 0;
        for (var object : objects)
            if (!object.isSleeping())
                stored[count++] =
                        object.getSingle(GeneratorComponent.class).orElseThrow().battery().stored();
        return stored;
    }

    /**
     * Sums copied charge values; no live Level or component is accessed during computation.
     *
     * @param stored detached charge values
     * @param context deferred command context, unused by this pure calculation
     * @return sum in charge units, using long to avoid int sum overflow
     */
    @Override
    public Long compute(int[] stored, WorldContext context) {
        long result = 0;
        for (int value : stored) result += value;
        return result;
    }

    /**
     * Publishes the completed statistic in the owner thread.
     *
     * @param result completed charge sum
     * @param context owner-thread context for optional world commands
     * @param manager owner-thread manager
     */
    @Override
    public void apply(Long result, WorldContext context, WorldObjectManager manager) {
        totalStored = result;
    }

    /**
     * @return last committed stored-charge sum; read in the owner thread
     */
    public long totalStored() {
        return totalStored;
    }
}
