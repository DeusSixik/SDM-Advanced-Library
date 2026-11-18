package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.annotations.EcsNoThreadSafe;
import dev.sixik.sdmal.api.ecs.annotations.EcsNoTickSkip;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Covers composition-cache invalidation, immutable projections and bounded slot reuse after
 * hot-path changes.
 */
class EcsHotPathTest {
    /**
     * Cached concrete restrictions disappear next tick after removal and apply to newly registered
     * systems.
     */
    @Test
    void policyCache_compositionAndSystemChanges_refreshConcreteRestrictions() {
        try (var manager = new EcsObjectManager()) {
            var submissions = new AtomicInteger();
            manager.scheduler()
                    .setExecutor(
                            job -> {
                                submissions.incrementAndGet();
                                job.run();
                            });
            var object = manager.create();
            var restricted = object.attach(new OwnerOnly());
            var system = new Reader("test:first", false);
            manager.scheduler().register(system);
            manager.tickAll();
            assertEquals(0, submissions.get());
            object.detachComponent(restricted);
            manager.tickAll();
            assertEquals(1, submissions.get());
            object.attach(new OwnerOnly());
            manager.tickAll();
            assertEquals(1, submissions.get());
            var added = new Reader("test:added", false);
            manager.scheduler().register(added);
            manager.tickAll();
            assertEquals(1, submissions.get());
            assertEquals(4, system.applied);
            assertEquals(1, added.applied);
        }
    }

    /**
     * Removed NoTickSkip subtypes must not keep an optional system mandatory forever.
     */
    @Test
    void policyCache_removedMandatorySubtype_restoresOptionalBudget() {
        try (var manager = new EcsObjectManager()) {
            manager.scheduler().setOptionalBudget(0);
            var object = manager.create();
            var required = object.attach(new Mandatory());
            var system = new Reader("test:optional", true);
            manager.scheduler().register(system);
            manager.tickAll();
            assertEquals(1, system.applied);
            object.detachComponent(required);
            manager.tickAll();
            assertEquals(1, system.applied);
            object.attach(new Mandatory());
            manager.tickAll();
            assertEquals(2, system.applied);
        }
    }

    /**
     * Projection caching keeps published snapshots independent from subsequent attachment
     * mutations.
     */
    @Test
    void components_cachedProjection_staysImmutableAcrossAttachDetachAndResort() {
        var object = new EcsObject();
        var first = object.attach(new Base());
        List<Component> old = object.components();
        assertSame(old, object.components());
        var second = object.attach(new Base());
        assertEquals(List.of(first), old);
        assertEquals(List.of(first, second), object.components());
        assertThrows(UnsupportedOperationException.class, () -> old.set(0, second));
        object.detachComponent(first);
        assertEquals(List.of(first), old);
        assertEquals(List.of(second), object.components());
        object.detach();
        assertTrue(object.components().isEmpty());
        assertEquals(List.of(first), old);
    }

    /** Public entry/component views cannot reveal the raw array that dispatches live callbacks. */
    @Test void publicSnapshots_arrayCopies_cannotCorruptOwnerComposition() {
        var object = new EcsObject();
        var component = object.attach(new Base());
        assertFalse(object.entries() instanceof dev.sixik.sdmal.core.utils.ObjectArrayView<?>);
        assertFalse(object.components() instanceof dev.sixik.sdmal.core.utils.ObjectArrayView<?>);
        Object[] entriesCopy = object.entries().toArray(); entriesCopy[0] = null;
        Object[] componentsCopy = object.components().toArray(); componentsCopy[0] = null;
        assertSame(component, object.entries().get(0).component());
        assertSame(component, object.components().get(0));
        assertThrows(UnsupportedOperationException.class, () -> object.entries().clear());
        assertTrue(object.has(Base.class));
    }

    /**
     * Publishing and discarding mixed private chains cannot lose slots or change per-phase order.
     */
    @Test
    void privateCommandChains_publishDiscardAndReuse_preserveCapacityAndOrder() {
        try (var manager = new EcsObjectManager()) {
            EcsContext context = manager.context();
            var accepted = new EcsContext.Batch();
            var discarded = new EcsContext.Batch();
            var trace = new java.util.ArrayList<Integer>();
            context.enter(accepted);
            context.enqueue(TickPhase.POST, () -> trace.add(3));
            context.enqueue(TickPhase.PRE, () -> trace.add(1));
            context.exit();
            context.enter(discarded);
            context.enqueue(TickPhase.NORMAL, () -> fail("discarded action executed"));
            context.enqueue(TickPhase.POST, () -> fail("discarded action executed"));
            context.exit();
            assertEquals(4, context.pendingCommands());
            context.discard(discarded);
            context.publish(accepted);
            context.enqueue(TickPhase.NORMAL, () -> trace.add(2));
            manager.tickAll();
            assertEquals(List.of(1, 2, 3), trace);
            var calls = new AtomicInteger();
            Runnable command = calls::incrementAndGet;
            for (int round = 0; round < 4; round++) {
                for (int i = 0; i < EcsContext.COMMAND_CAPACITY; i++)
                    context.enqueue(TickPhase.POST, command);
                manager.tickAll();
                assertEquals(0, context.pendingCommands());
            }
            assertEquals(EcsContext.COMMAND_CAPACITY * 4, calls.get());
        }
    }

    /**
     * Multiple producer threads can fill and repeatedly recycle the shared capacity without
     * duplicate execution.
     *
     * @throws Exception if a bounded producer operation fails or times out
     */
    @Test
    void concurrentCommandProducers_repeatedCapacityReuse_executesEveryActionOnce()
            throws Exception {
        var workers = Executors.newFixedThreadPool(4);
        try (var manager = new EcsObjectManager()) {
            var context = manager.context();
            var completed = new AtomicInteger();
            Thread owner = Thread.currentThread();
            Runnable action =
                    () -> {
                        assertSame(owner, Thread.currentThread());
                        completed.incrementAndGet();
                    };
            for (int round = 0; round < 3; round++) {
                var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int producer = 0; producer < 4; producer++) {
                    jobs.add(
                            workers.submit(
                                    () -> {
                                        for (int i = 0; i < EcsContext.COMMAND_CAPACITY / 4; i++)
                                            context.enqueue(TickPhase.POST, action);
                                    }));
                }
                for (var job : jobs) job.get(5, TimeUnit.SECONDS);
                assertEquals(EcsContext.COMMAND_CAPACITY, context.pendingCommands());
                manager.tickAll();
                assertEquals(EcsContext.COMMAND_CAPACITY * (round + 1), completed.get());
                assertEquals(0, context.pendingCommands());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    /**
     * Closing releases published actions; private reservations are released at their owner's
     * barrier.
     *
     * @throws Exception if reference storage cannot be inspected
     */
    @Test
    void commandStorage_closeAndDiscard_clearRetainedUserReferences() throws Exception {
        var manager = new EcsObjectManager();
        var context = manager.context();
        var batch = new EcsContext.Batch();
        context.enqueue(TickPhase.PRE, () -> fail("closed action executed"));
        context.enter(batch);
        context.enqueue(TickPhase.POST, () -> fail("closed private action executed"));
        context.exit();
        manager.close();
        assertEquals(1, context.pendingCommands());
        context.discard(batch);
        assertEquals(0, context.pendingCommands());
        var field = EcsContext.class.getDeclaredField("actions");
        field.setAccessible(true);
        assertNull(field.get(context));
    }

    /**
     * Resource base class used by concrete subtype restriction checks.
     */
    private static class Base extends Component {}

    /**
     * Concrete type requiring owner-thread execution.
     */
    @EcsNoThreadSafe
    private static final class OwnerOnly extends Base {}

    /**
     * Concrete type forbidding optional budget deferral.
     */
    @EcsNoTickSkip
    private static final class Mandatory extends Base {}

    /**
     * Explicit reader counting successful commits without introducing unrelated component mutation.
     */
    private static final class Reader implements EcsSystem<Void, Void> {
        /**
         * Stable system id.
         */
        private final String id;

        /**
         * Whether budget deferral is allowed.
         */
        private final boolean optional;

        /**
         * Successful owner-thread commits.
         */
        int applied;

        /**
         * @param id stable id
         * @param optional whether budget deferral is allowed
         */
        Reader(String id, boolean optional) {
            this.id = id;
            this.optional = optional;
        }

        /**
         * @return stable id
         */
        @Override
        public String id() {
            return id;
        }

        /**
         * @return access to Base and every concrete subtype
         */
        @Override
        public EcsAccess access() {
            return EcsAccess.NONE.read(Base.class);
        }

        /**
         * @return explicit worker permission
         */
        @Override
        public boolean parallel() {
            return true;
        }

        /**
         * @return configured deferral permission
         */
        @Override
        public boolean mayDefer() {
            return optional;
        }

        /**
         * @param context owner context
         * @param manager owner manager
         * @return empty input
         */
        @Override
        public Void prepare(EcsContext context, EcsObjectManager manager) {
            return null;
        }

        /**
         * @param input empty input
         * @param context restricted context
         * @return empty result
         */
        @Override
        public Void compute(Void input, EcsContext context) {
            return null;
        }

        /**
         * @param result empty result
         * @param context owner context
         * @param manager owner manager
         */
        @Override
        public void apply(Void result, EcsContext context, EcsObjectManager manager) {
            applied++;
        }
    }
}
