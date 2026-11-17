package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.annotations.EcsNoThreadSafe;

import org.junit.jupiter.api.Test;

/**
 * Verifies chunk-like activation gates across legacy and automatic snapshot dispatch.
 */
class WorldObjectActivationTest {
    /**
     * A gate is sampled once for all component groups and legacy callbacks in one world tick.
     */
    @Test
    void tickCondition_multipleGroups_samplesOncePerTick() {
        try (var manager = new WorldObjectManager()) {
            var object = manager.create();
            var first = object.attach(new Counter());
            var second = object.attach(new OtherCounter());
            var legacy = object.attach(new Legacy());
            int[] calls = {0};
            object.setTickCondition(
                    () -> {
                        calls[0]++;
                        return true;
                    });
            manager.tickAll();
            assertEquals(1, calls[0]);
            assertEquals(1, first.value);
            assertEquals(1, second.value);
            assertEquals(1, legacy.value);
            manager.tickAll();
            assertEquals(2, calls[0]);
            assertEquals(2, first.value);
        }
    }

    /**
     * Inactive chunks stop both simulation paths and object-local deferred work until reactivation.
     */
    @Test
    void tickCondition_inactiveHost_suspendsLegacySnapshotsAndDeferredWork() {
        try (var manager = new WorldObjectManager()) {
            var object = manager.create();
            var snapshot = object.attach(new Counter());
            var legacy = object.attach(new Legacy());
            boolean[] active = {false};
            int[] deferred = {0};
            object.setTickCondition(() -> active[0]);
            object.defer(() -> deferred[0]++);
            manager.tickAll();
            manager.tickAll();
            assertEquals(0, snapshot.value);
            assertEquals(0, legacy.value);
            assertEquals(0, deferred[0]);
            active[0] = true;
            manager.tickAll();
            assertEquals(1, snapshot.value);
            assertEquals(1, legacy.value);
            assertEquals(1, deferred[0]);
        }
    }

    /**
     * External activity does not override explicit sleeping or a user-selected tick interval.
     */
    @Test
    void tickCondition_sleepAndInterval_preservesExplicitSchedule() {
        try (var manager = new WorldObjectManager()) {
            var object = manager.create();
            var counter = object.attach(new Counter());
            object.setTickCondition(() -> true);
            object.setTickInterval(3);
            manager.tickAll();
            manager.tickAll();
            manager.tickAll();
            assertEquals(1, counter.value);
            object.sleep();
            manager.tickAll();
            assertEquals(1, counter.value);
            object.wake();
            manager.tickAll();
            assertEquals(2, counter.value);
        }
    }

    /**
     * Replacing the gate invalidates an already cached decision in the same manager tick.
     */
    @Test
    void tickCondition_replacement_invalidatesCachedDecision() {
        try (var manager = new WorldObjectManager()) {
            var object = manager.create();
            object.setTickCondition(() -> false);
            assertFalse(object.isTickActive(manager));
            object.setTickCondition(() -> true);
            assertTrue(object.isTickActive(manager));
            object.setTickCondition(null);
            assertTrue(object.isTickActive(manager));
            object.detach();
            assertThrows(DetachedException.class, () -> object.setTickCondition(null));
        }
    }

    /**
     * Work deferred while a chunk was active cannot bypass a later inactive gate.
     */
    @Test
    void tickCondition_pendingOptionalWork_waitsForReactivation() {
        try (var manager = new WorldObjectManager()) {
            var object = manager.create();
            var counter = object.attach(new OptionalCounter());
            boolean[] active = {true};
            object.setTickCondition(() -> active[0]);
            manager.scheduler().setOptionalBudget(0);
            manager.tickAll();
            assertEquals(0, counter.value);
            active[0] = false;
            manager.scheduler().setOptionalBudget(100);
            manager.tickAll();
            assertEquals(0, counter.value);
            active[0] = true;
            manager.tickAll();
            assertEquals(1, counter.value);
        }
    }

    /**
     * Reusable snapshot avoids allocation and verifies the automatic capture/compute/apply path.
     */
    @EcsNoThreadSafe
    private static class Counter extends AbstractWorldObjectComponent
            implements SnapshotTickableComponent<int[], int[]> {
        /**
         * Current committed count.
         */
        int value;

        /**
         * One exclusive input/result slot reused after each barrier.
         */
        final int[] data = new int[1];

        /**
         * @param context owner context @return copied input
         */
        @Override
        public int[] capture(WorldContext context) {
            data[0] = value;
            return data;
        }

        /**
         * @param data independent input @param context restricted compute context @return computed
         *     input buffer
         */
        @Override
        public int[] compute(int[] data, WorldContext context) {
            data[0]++;
            return data;
        }

        /**
         * @param data computed buffer @param context owner context
         */
        @Override
        public void apply(int[] data, WorldContext context) {
            value = data[0];
        }
    }

    /**
     * Distinct type creates an independent automatic component group.
     */
    private static final class OtherCounter extends Counter {}

    /**
     * Allows the scheduler to retain an unstarted dispatch for a later tick.
     */
    private static final class OptionalCounter extends Counter {
        /**
         * @return true to opt into budget-based deferral
         */
        @Override
        public boolean mayDeferTick() {
            return true;
        }
    }

    /**
     * Old-style callback shares the same cached activation decision.
     */
    private static final class Legacy extends AbstractWorldObjectComponent
            implements TickableComponent {
        /**
         * Number of dispatches received.
         */
        int value;

        /**
         * @param tick manager sequence
         */
        @Override
        public void onTick(long tick) {
            value++;
        }
    }
}
