package dev.sixik.sdmal.core.ecs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises exclusive ownership, world-thread confinement and predictable bounded scheduling. */
class WorldObjectSchedulingTest {
    /** One object cannot receive ticks from two managers and ownership is released after removal. */
    @Test void add_sameObjectAcrossManagers_rejectsSecondOwner() {
        try (var first = new WorldObjectManager(); var second = new WorldObjectManager()) {
            WorldObject object = first.create();
            assertSame(first, object.manager().orElseThrow());
            assertThrows(IllegalStateException.class, () -> second.add(object));
            assertEquals(0, second.size());
            object.detach();
            assertEquals(0, first.size());
            assertTrue(object.manager().isEmpty());
            assertTrue(object.isDetached());
        }
    }

    /** Failed priority evaluation leaves both membership and component ownership unchanged. */
    @Test void attach_throwingPriority_doesNotBindInvisibleComponent() {
        WorldObject object = new WorldObject();
        Counter existing = object.attach(new Counter());
        var broken = new AbstractWorldObjectComponent() {
            /**
             * Fails while building the proposed order.
             * @return never returns
             * @throws IllegalStateException always, for this fixture
             */
            @Override public int priority() { throw new IllegalStateException("fixture"); }
        };
        assertThrows(IllegalStateException.class, () -> object.attach(broken));
        assertTrue(broken.owner().isEmpty());
        assertSame(object, existing.owner().orElseThrow());
        assertEquals(List.of(existing), object.components());
        object.detach();
    }

    /** Non-fatal detach failures still release manager ownership and all queued work. */
    @Test void remove_failingLifecycle_releasesOwnership() {
        try (var manager = new WorldObjectManager()) {
            WorldObject object = manager.create();
            object.attach(new AbstractWorldObjectComponent() {
                /**
                 * Fails after membership has been removed.
                 * @throws IllegalStateException always, for this fixture
                 */
                @Override public void onDetached() { throw new IllegalStateException("fixture"); }
            });
            assertThrows(WorldObjectException.class, () -> manager.remove(object.id()));
            assertTrue(object.manager().isEmpty());
            assertTrue(object.isDetached());
            assertEquals(0, manager.size());
        }
    }

    /**
     * Workers submit commands instead of mutating world-owned structure or invoking lifecycle callbacks.
     * @throws Exception if worker execution fails or times out
     */
    @Test void execute_fromWorker_mutatesOnOwnerThread() throws Exception {
        try (var manager = new WorldObjectManager()) {
            WorldObject object = manager.create();
            Thread owner = Thread.currentThread();
            var workers = Executors.newSingleThreadExecutor();
            try {
                workers.submit(() -> {
                    assertThrows(IllegalStateException.class, manager::create);
                    assertThrows(IllegalStateException.class, manager::tickAll);
                    assertThrows(IllegalStateException.class, manager::close);
                    assertThrows(IllegalStateException.class, () -> object.attach(new Counter()));
                    assertThrows(IllegalStateException.class, object::detach);
                    object.defer(() -> assertSame(owner, Thread.currentThread()));
                    manager.execute(() -> {
                        assertSame(owner, Thread.currentThread());
                        object.attach(new Counter());
                    });
                }).get(5, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
            assertEquals(0, object.size());
            manager.tickAll();
            assertEquals(1, object.getSingle(Counter.class).orElseThrow().ticks);
        }
    }

    /** Self-rescheduling commands and deferred tasks run in bounded batches on subsequent ticks. */
    @Test void queues_selfRescheduling_preservesTickBudget() {
        try (var manager = new WorldObjectManager()) {
            manager.setCommandBudget(2);
            AtomicInteger commands = new AtomicInteger();
            Runnable[] repeating = new Runnable[1];
            repeating[0] = () -> { commands.incrementAndGet(); manager.execute(repeating[0]); };
            manager.execute(repeating[0]);
            manager.execute(commands::incrementAndGet);
            manager.execute(commands::incrementAndGet);
            manager.tickAll();
            assertEquals(2, commands.get());
            assertEquals(2, manager.pendingCommands());
            manager.tickAll();
            assertEquals(4, commands.get());
            WorldObject object = manager.create();
            AtomicInteger deferred = new AtomicInteger();
            Runnable[] self = new Runnable[1];
            self[0] = () -> { deferred.incrementAndGet(); object.defer(self[0]); };
            object.defer(self[0]);
            assertEquals(1, object.drainDeferred(10));
            assertEquals(1, deferred.get());
            assertTrue(object.hasPendingDeferred());
            assertThrows(IllegalArgumentException.class, () -> object.drainDeferred(0));
        }
    }

    /** Full queues reject new work and shutdown discards already queued commands. */
    @Test void queues_capacityAndClose_rejectUnboundedWork() {
        WorldObjectManager manager = new WorldObjectManager();
        WorldObject object = manager.create();
        for (int i = 0; i < 4096; i++) manager.execute(() -> fail("Closed work must not run"));
        assertThrows(RejectedExecutionException.class, () -> manager.execute(() -> {}));
        for (int i = 0; i < 1024; i++) object.defer(() -> {});
        assertThrows(RejectedExecutionException.class, () -> object.defer(() -> {}));
        manager.close();
        assertEquals(0, manager.pendingCommands());
        assertThrows(RejectedExecutionException.class, () -> manager.execute(() -> {}));
    }

    /** A recursive command fails its batch without starting a second world tick. */
    @Test void tick_recursiveCommand_doesNotAdvanceTwice() {
        try (var manager = new WorldObjectManager()) {
            Counter counter = manager.create().attach(new Counter());
            manager.execute(manager::tickAll);
            assertThrows(WorldObjectException.class, manager::tickAll);
            assertEquals(1, manager.lastTick());
            assertEquals(1, counter.ticks);
        }
    }

    /** Sleep removes an object from the active snapshot; wake restores registration order and interval. */
    @Test void activity_sleepIntervalAndWake_preserveSequence() {
        try (var manager = new WorldObjectManager()) {
            WorldObject object = manager.create();
            Counter counter = object.attach(new Counter());
            object.setTickInterval(3);
            for (int i = 0; i < 5; i++) manager.tickAll();
            assertEquals(List.of(1L, 4L), counter.sequence);
            object.sleep();
            assertEquals(0, manager.activeSize());
            object.defer(() -> counter.deferred++);
            manager.tickAll();
            assertEquals(0, counter.deferred);
            object.wake();
            assertEquals(1, manager.activeSize());
            manager.tickAll();
            assertEquals(List.of(1L, 4L, 7L), counter.sequence);
            assertEquals(1, counter.deferred);
            assertThrows(IllegalArgumentException.class, () -> object.setTickInterval(0));
        }
    }

    /** Stable membership iteration excludes additions and removals performed during the current pass. */
    @Test void tick_membershipChanges_areDeterministic() {
        try (var manager = new WorldObjectManager()) {
            List<String> order = new ArrayList<>();
            WorldObject first = manager.create();
            WorldObject second = manager.create();
            first.attach(new Action(tick -> {
                order.add("first");
                if (tick == 1) {
                    manager.remove(second.id());
                    manager.create().attach(new Action(ignored -> order.add("third")));
                }
            }));
            second.attach(new Action(tick -> order.add("second")));
            manager.tickAll();
            assertEquals(List.of("first"), order);
            manager.tickAll();
            assertEquals(List.of("first", "first", "third"), order);
            assertEquals(2, manager.size());
        }
    }

    /** Queries include sleeping members, support class conjunctions and exact registered types. */
    @Test void query_componentConjunctions_includeSleepingObjects() {
        try (var manager = new WorldObjectManager()) {
            var type = new WorldObjectComponentType<>("test:counter", Counter.class,
                WorldObjectComponentType.DuplicatePolicy.REJECT, Counter::new);
            WorldObject first = manager.create();
            first.attach(new Counter(), type);
            first.attach(new Marker());
            WorldObject second = manager.create();
            second.attach(new Counter());
            first.sleep();
            assertEquals(List.of(first, second), manager.query(Counter.class));
            assertEquals(List.of(first), manager.query(Counter.class, Marker.class));
            assertEquals(List.of(first), manager.queryTypes(type));
            assertEquals(List.of(first, second), manager.query());
            assertThrows(UnsupportedOperationException.class, () -> manager.query().clear());
            first.detachAll(Marker.class);
            assertTrue(manager.query(Marker.class).isEmpty());
        }
    }

    /** Fatal linkage errors escape instead of being retried and logged as ordinary component failures. */
    @Test void tick_fatalError_propagates() {
        try (var manager = new WorldObjectManager()) {
            Counter counter = manager.create().attach(new Counter());
            counter.fatal = true;
            assertThrows(LinkageError.class, manager::tickAll);
        }
    }

    /** Counter used to observe tick sequence and deferred completion. */
    private static final class Counter extends AbstractWorldObjectComponent implements TickableComponent {
        /** Number of component dispatches. */
        int ticks;
        /** Number of completed deferred actions. */
        int deferred;
        /** Captured manager sequences in observation order. */
        final List<Long> sequence = new ArrayList<>();
        /** Whether to emit a fatal linkage failure for policy tests. */
        boolean fatal;
        /**
         * Records a component tick.
         * @param tick manager sequence
         * @throws LinkageError when fatal is set
         */
        @Override public void onTick(long tick) {
            if (fatal) throw new LinkageError("fixture");
            ticks++;
            sequence.add(tick);
        }
    }

    /** Marker component used by multi-component queries. */
    private static final class Marker extends AbstractWorldObjectComponent {}

    /** Performs a supplied owner-thread mutation during a component tick. */
    private static final class Action extends AbstractWorldObjectComponent implements TickableComponent {
        /** Tick operation supplied by the test. */
        private final LongConsumer action;
        /**
         * Creates a mutation fixture.
         * @param action tick operation, non-null
         */
        private Action(LongConsumer action) { this.action = action; }
        /**
         * Runs the supplied operation.
         * @param tick manager sequence
         */
        @Override public void onTick(long tick) { action.accept(tick); }
    }
}
