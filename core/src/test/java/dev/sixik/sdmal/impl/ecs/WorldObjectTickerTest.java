package dev.sixik.sdmal.impl.ecs;

import dev.sixik.sdmal.api.ecs.AbstractWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.TickableComponent;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the dispatch policy used by both world tick mixins. */
class WorldObjectTickerTest {

    /** Separate worlds advance only their own components and monotonically increasing sequences. */
    @Test void tick_runningWorlds_keepManagersIndependent() {
        try (WorldObjectManager server = new WorldObjectManager(); WorldObjectManager client = new WorldObjectManager()) {
            Counter serverCounter = server.create().attach(new Counter());
            Counter clientCounter = client.create().attach(new Counter());
            WorldObjectTicker.tick(server, true);
            WorldObjectTicker.tick(server, true);
            WorldObjectTicker.tick(client, true);
            assertEquals(2, serverCounter.ticks);
            assertEquals(2, serverCounter.lastTick);
            assertEquals(1, clientCounter.ticks);
            assertEquals(1, clientCounter.lastTick);
        }
    }

    /** Frozen world ticks preserve sequence and deferred work until simulation resumes. */
    @Test void tick_frozenWorld_defersSimulationUntilResume() {
        try (WorldObjectManager manager = new WorldObjectManager()) {
            var object = manager.create();
            Counter counter = object.attach(new Counter());
            object.defer(() -> counter.deferred++);
            WorldObjectTicker.tick(manager, false);
            assertEquals(0, manager.lastTick());
            assertEquals(0, counter.ticks);
            assertEquals(0, counter.deferred);
            assertTrue(object.hasPendingDeferred());
            WorldObjectTicker.tick(manager, true);
            assertEquals(1, counter.lastTick);
            assertEquals(1, counter.ticks);
            assertEquals(1, counter.deferred);
        }
    }

    /** Component failures do not escape into Minecraft or prevent other objects and later ticks. */
    @Test void tick_failingComponent_reportsFailureAndContinues() {
        try (WorldObjectManager manager = new WorldObjectManager()) {
            Counter failing = manager.create().attach(new Counter());
            failing.fail = true;
            Counter healthy = manager.create().attach(new Counter());
            assertDoesNotThrow(() -> WorldObjectTicker.tick(manager, true));
            assertEquals(1, failing.ticks);
            assertEquals(1, healthy.ticks);
            failing.fail = false;
            assertDoesNotThrow(() -> WorldObjectTicker.tick(manager, true));
            assertEquals(2, failing.ticks);
            assertEquals(2, healthy.ticks);
            assertEquals(2, manager.lastTick());
        }
    }

    /** Closed managers never resume ticking even when vanilla invokes another world tick. */
    @Test void tick_closedManager_leavesSequenceAndComponentsUnchanged() {
        WorldObjectManager manager = new WorldObjectManager();
        Counter counter = manager.create().attach(new Counter());
        WorldObjectTicker.tick(manager, true);
        manager.close();
        WorldObjectTicker.tick(manager, true);
        assertEquals(1, counter.ticks);
        assertEquals(1, manager.lastTick());
        assertFalse(counter.isAttached());
    }

    /** Stack traces are limited during repeated failures and re-enabled immediately after recovery. */
    @Test void errors_repeatedFailures_throttleAndReset() {
        assertFalse(WorldObjectTicker.shouldReport(0));
        assertTrue(WorldObjectTicker.shouldReport(1));
        assertFalse(WorldObjectTicker.shouldReport(2));
        assertFalse(WorldObjectTicker.shouldReport(99));
        assertTrue(WorldObjectTicker.shouldReport(100));
        assertFalse(WorldObjectTicker.shouldReport(101));
        try (var manager = new WorldObjectManager()) {
            Counter counter = manager.create().attach(new Counter());
            counter.fail = true;
            assertThrows(dev.sixik.sdmal.api.ecs.WorldObjectException.class, manager::tickAll);
            assertThrows(dev.sixik.sdmal.api.ecs.WorldObjectException.class, manager::tickAll);
            assertEquals(2, manager.consecutiveTickFailures());
            counter.fail = false;
            manager.tickAll();
            assertEquals(0, manager.consecutiveTickFailures());
            counter.fail = true;
            assertThrows(dev.sixik.sdmal.api.ecs.WorldObjectException.class, manager::tickAll);
            assertEquals(1, manager.consecutiveTickFailures());
        }
    }

    /** Records dispatches and optionally fails a component tick. */
    private static final class Counter extends AbstractWorldObjectComponent implements TickableComponent {
        /** Number of observed ticks, initially zero. */
        private int ticks;
        /** Last observed manager sequence, initially zero. */
        private long lastTick;
        /** Number of completed deferred actions, initially zero. */
        private int deferred;
        /** Whether to throw after recording each tick. */
        private boolean fail;

        /**
         * Records one tick and optionally fails.
         * @param tick manager sequence, starting at one
         * @throws IllegalStateException when this fixture is configured to fail
         */
        @Override public void onTick(long tick) {
            ticks++;
            lastTick = tick;
            if (fail) throw new IllegalStateException("fixture");
        }
    }
}
