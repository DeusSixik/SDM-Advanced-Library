package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.annotations.EcsNoTickSkip;
import dev.sixik.sdmal.impl.ecs.WorldObjectTicker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks fixed phase queues, inherited deadlines, lifecycle guards and compatibility with serial
 * component ticks.
 */
class WorldContextTest {
    /**
     * Callbacks cannot recursively advance phases or complete an active object dispatch.
     */
    @Test
    void callbacks_recursivePhaseTransitions_areRejected() {
        try (var manager = new WorldObjectManager()) {
            manager.worldContext()
                    .enqueue(
                            TickPhase.PRE,
                            () -> {
                                assertThrows(IllegalStateException.class, manager::tickNormal);
                                assertThrows(IllegalStateException.class, manager::endTick);
                            });
            manager.create()
                    .attach(
                            new Legacy(
                                    () ->
                                            assertThrows(
                                                    IllegalStateException.class,
                                                    manager::endTick)));
            manager.tickAll();
            assertFalse(manager.isTicking());
        }
    }

    /**
     * Required command callbacks cannot drop their deadline by enqueueing another ordinary command.
     */
    @Test
    void requiredCommand_nestedSubmission_preservesDeadline() {
        try (var manager = new WorldObjectManager()) {
            manager.worldContext()
                    .enqueueRequired(
                            TickPhase.POST,
                            () -> {
                                assertThrows(
                                        IllegalStateException.class,
                                        () ->
                                                manager.worldContext()
                                                        .enqueue(TickPhase.POST, () -> {}));
                            });
            manager.tickAll();
            assertEquals(0, manager.worldContext().pendingCommands());
        }
    }

    /**
     * Commands and old components share exactly one monotonic world tick across the three
     * boundaries.
     */
    @Test
    void phases_commandsAndLegacyComponents_haveStableOrder() {
        try (var manager = new WorldObjectManager()) {
            List<String> trace = new ArrayList<>();
            var context = manager.worldContext();
            context.enqueue(TickPhase.POST, () -> trace.add("post"));
            context.enqueue(TickPhase.NORMAL, () -> trace.add("normal"));
            context.enqueue(TickPhase.PRE, () -> trace.add("pre"));
            manager.create().attach(new Legacy(() -> trace.add("legacy")));
            manager.beginTick();
            assertEquals(List.of("pre"), trace);
            manager.tickNormal();
            assertEquals(List.of("pre", "normal"), trace);
            manager.endTick();
            assertEquals(List.of("pre", "normal", "legacy", "post"), trace);
            assertEquals(1, manager.lastTick());
            assertFalse(manager.isTicking());
            assertNull(context.phase());
        }
    }

    /**
     * Work enqueued into its own draining batch waits for the next tick; later phases remain
     * available.
     */
    @Test
    void enqueue_duringDrain_defersSamePhaseButKeepsFuturePhase() {
        try (var manager = new WorldObjectManager()) {
            List<String> trace = new ArrayList<>();
            var context = manager.worldContext();
            context.enqueue(
                    TickPhase.PRE,
                    () -> {
                        trace.add("first");
                        context.enqueue(TickPhase.PRE, () -> trace.add("next"));
                        context.enqueue(TickPhase.POST, () -> trace.add("post"));
                        assertThrows(
                                IllegalStateException.class,
                                () -> context.enqueueRequired(TickPhase.PRE, () -> {}));
                    });
            manager.tickAll();
            assertEquals(List.of("first", "post"), trace);
            manager.tickAll();
            assertEquals(List.of("first", "post", "next"), trace);
        }
    }

    /**
     * The separate legacy command budget cannot silently truncate a selected world command batch.
     */
    @Test
    void queue_capacityIsBoundedAndSelectedBatchCompletes() {
        try (var manager = new WorldObjectManager()) {
            manager.setCommandBudget(1);
            AtomicInteger completed = new AtomicInteger();
            Runnable action = completed::incrementAndGet;
            for (int i = 0; i < WorldContext.COMMAND_CAPACITY; i++)
                manager.worldContext().enqueueRequired(TickPhase.POST, action);
            assertThrows(
                    RejectedExecutionException.class,
                    () -> manager.worldContext().enqueue(TickPhase.POST, action));
            manager.tickAll();
            assertEquals(WorldContext.COMMAND_CAPACITY, completed.get());
            assertEquals(0, manager.worldContext().pendingCommands());
        }
    }

    /**
     * Nonfatal command failures do not prevent remaining commands and later phases from running.
     */
    @Test
    void commandFailure_drainsRemainingWorkAndResetsTickState() {
        try (var manager = new WorldObjectManager()) {
            AtomicInteger completed = new AtomicInteger();
            manager.worldContext()
                    .enqueue(
                            TickPhase.PRE,
                            () -> {
                                throw new IllegalArgumentException("broken");
                            });
            manager.worldContext().enqueue(TickPhase.PRE, completed::incrementAndGet);
            manager.worldContext().enqueue(TickPhase.POST, completed::incrementAndGet);
            assertThrows(WorldObjectException.class, manager::tickAll);
            assertEquals(2, completed.get());
            assertFalse(manager.isTicking());
            manager.tickAll();
            assertEquals(0, manager.consecutiveTickFailures());
        }
    }

    /**
     * A delayed command cannot change a replacement object that happens to have the original UUID.
     */
    @Test
    void guardedCommand_removedAndRecreatedObject_discardsStaleResult() {
        try (var manager = new WorldObjectManager()) {
            var original = manager.create();
            AtomicInteger applied = new AtomicInteger();
            manager.worldContext().enqueue(TickPhase.POST, original, applied::incrementAndGet);
            manager.remove(original.id());
            manager.add(new WorldObject(original.id()));
            manager.tickAll();
            assertEquals(0, applied.get());
        }
    }

    /**
     * Existing NoTickSkip components also pass their deadline to world commands without moving
     * their callback.
     */
    @Test
    void legacyMandatoryComponent_rejectsPastPhaseAndCommitsPost() {
        try (var manager = new WorldObjectManager()) {
            AtomicInteger applied = new AtomicInteger();
            manager.create()
                    .attach(
                            new RequiredLegacy(
                                    () -> {
                                        assertThrows(
                                                IllegalStateException.class,
                                                () ->
                                                        manager.worldContext()
                                                                .enqueue(
                                                                        TickPhase.PRE,
                                                                        applied::incrementAndGet));
                                        manager.worldContext()
                                                .enqueue(TickPhase.POST, applied::incrementAndGet);
                                    }));
            manager.tickAll();
            assertEquals(1, applied.get());
        }
    }

    /**
     * Freeze prevents all phases; a tick already begun completes even if freeze is enabled during
     * the tick.
     */
    @Test
    void worldIntegration_freezeAndMidTickFreeze_preserveTickBoundaries() {
        try (var manager = new WorldObjectManager()) {
            AtomicInteger applied = new AtomicInteger();
            manager.worldContext().enqueue(TickPhase.PRE, applied::incrementAndGet);
            WorldObjectTicker.begin(manager, false);
            WorldObjectTicker.normal(manager);
            WorldObjectTicker.tick(manager, false);
            assertEquals(0, manager.lastTick());
            WorldObjectTicker.begin(manager, true);
            WorldObjectTicker.normal(manager);
            WorldObjectTicker.tick(manager, false);
            assertEquals(1, manager.lastTick());
            assertEquals(1, applied.get());
        }
    }

    /**
     * Closing clears all command references and prevents background work from queuing into an
     * unloaded world.
     */
    @Test
    void close_clearsCommandsAndRejectsSubmission() {
        var manager = new WorldObjectManager();
        var context = manager.worldContext();
        context.enqueue(TickPhase.POST, () -> fail("unloaded command"));
        manager.close();
        assertEquals(0, context.pendingCommands());
        assertThrows(
                RejectedExecutionException.class, () -> context.enqueue(TickPhase.PRE, () -> {}));
    }

    /**
     * Minimal serial callback used to verify compatibility with old components.
     */
    private static class Legacy extends AbstractWorldObjectComponent implements TickableComponent {
        /**
         * Owner-thread tick action.
         */
        private final Runnable action;

        /**
         * @param action tick callback
         */
        Legacy(Runnable action) {
            this.action = action;
        }

        /**
         * @param tick monotonic manager sequence
         */
        @Override
        public void onTick(long tick) {
            action.run();
        }
    }

    /**
     * Inherited deadline applies to ordinary onTick callbacks as well as new system declarations.
     */
    @EcsNoTickSkip
    private static final class RequiredLegacy extends Legacy {
        /**
         * @param action tick callback
         */
        RequiredLegacy(Runnable action) {
            super(action);
        }
    }
}
