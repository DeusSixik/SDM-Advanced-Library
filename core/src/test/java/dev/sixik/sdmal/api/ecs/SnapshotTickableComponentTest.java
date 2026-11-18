package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.annotations.EcsNoThreadSafe;
import dev.sixik.sdmal.api.ecs.annotations.EcsNoTickSkip;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises automatic component grouping, interval selection, disjoint worker chunks and
 * exact-attachment commit guards.
 */
class SnapshotTickableComponentTest {
    /**
     * Commands spawned by a component's published callback retain its exact attachment guard.
     */
    @Test
    void nestedWorldCommand_componentDetachedBeforeLaterPhase_discardsInheritedCommand() {
        try (var manager = new EcsObjectManager()) {
            var object = manager.create();
            var commands = new AtomicInteger();
            object.attach(
                    new Counter() {
                        /**
                         * @return PRE so the first command can publish a later-phase command
                         */
                        @Override
                        public TickPhase tickPhase() {
                            return TickPhase.PRE;
                        }

                        /**
                         * @param result completed scalar
                         * @param context owner context
                         */
                        @Override
                        public void apply(Integer result, EcsContext context) {
                            super.apply(result, context);
                            context.enqueue(
                                    TickPhase.PRE,
                                    () -> {
                                        context.enqueue(TickPhase.POST, commands::incrementAndGet);
                                        object.detach();
                                    });
                        }
                    });
            manager.tickAll();
            assertEquals(0, commands.get());
            assertEquals(0, manager.context().pendingCommands());
            assertTrue(object.isDetached());
        }
    }

    /**
     * Automatic command guards are rechecked at execution, after preceding callbacks may remove
     * their component.
     */
    @Test
    void priorWorldCommand_detachesComponent_beforeItsPublishedCommandExecutes() {
        try (var manager = new EcsObjectManager()) {
            var firstObject = manager.create();
            var secondObject = manager.create();
            var first = firstObject.attach(new CommandRemovingCounter());
            var second = secondObject.attach(new CommandRemovingCounter());
            first.target = secondObject;
            manager.tickAll();
            assertEquals(1, first.commands.get());
            assertEquals(0, second.commands.get());
            assertTrue(secondObject.isDetached());
        }
    }

    /**
     * A managed component ticks without any explicit system, preserving intervals and sleep/wake
     * semantics.
     */
    @Test
    void attach_withoutSystemRegistration_automaticallyTicksAndRespectsObjectSchedule() {
        try (var manager = new EcsObjectManager()) {
            var object = manager.create();
            object.setTickInterval(3);
            Counter component = object.attach(new Counter());
            manager.tickAll();
            manager.tickAll();
            manager.tickAll();
            assertEquals(1, component.value);
            manager.tickAll();
            assertEquals(2, component.value);
            object.sleep();
            manager.tickAll();
            manager.tickAll();
            manager.tickAll();
            assertEquals(2, component.value);
            object.wake();
            manager.tickAll();
            assertEquals(3, component.value);
        }
    }

    /**
     * Exact phase choice is honored even though the object's serial dispatch advances its interval
     * before POST.
     */
    @Test
    void postComponent_usesBeginningOfTickSchedule() {
        try (var manager = new EcsObjectManager()) {
            var object = manager.create();
            object.setTickInterval(4);
            var component = object.attach(new PostCounter());
            manager.beginTick();
            manager.tickNormal();
            assertEquals(0, component.value);
            manager.endTick();
            assertEquals(1, component.value);
            for (int i = 0; i < 4; i++) manager.tickAll();
            assertEquals(2, component.value);
        }
    }

    /**
     * Each component captures before any same-type apply and all results commit in attachment
     * order.
     */
    @Test
    void sameType_capturesAllInputsBeforeApplyAndCommitsCommandsStably() {
        try (var manager = new EcsObjectManager()) {
            List<String> trace = new ArrayList<>();
            manager.create().attach(new OrderedCounter("one", trace));
            manager.create().attach(new OrderedCounter("two", trace));
            manager.tickAll();
            assertEquals(
                    List.of(
                            "capture-one",
                            "capture-two",
                            "apply-one",
                            "apply-two",
                            "command-one",
                            "command-two"),
                    trace);
        }
    }

    /**
     * Independent instances of one concrete type use multiple jobs instead of being serialized by
     * type-level hazards.
     */
    @Test
    void sameConcreteType_largeBatch_computesOverlappingChunks() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Runtime.getRuntime().availableProcessors() > 1,
                "Multiple worker chunks require more than one available processor");
        var executor = Executors.newFixedThreadPool(2);
        try (var manager = new EcsObjectManager()) {
            manager.scheduler().setExecutor(executor);
            CountDownLatch chunks = new CountDownLatch(2);
            for (int i = 0; i < 128; i++)
                manager.create().attach(new OverlappingCounter(i, chunks));
            manager.tickAll();
            for (var object : manager.snapshot())
                assertEquals(1, object.getSingle(OverlappingCounter.class).orElseThrow().value);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Manager closure during capture must release private buffers without running computations or
     * queued mutations.
     */
    @Test
    void closeDuringCapture_discardsComponentCommandsAndStopsDispatch() {
        var manager = new EcsObjectManager();
        manager.create()
                .attach(
                        new Counter() {
                            @Override
                            public Integer capture(EcsContext context) {
                                context.enqueue(
                                        TickPhase.POST,
                                        () -> fail("command survived world unload"));
                                manager.close();
                                return 0;
                            }

                            @Override
                            public Integer compute(Integer input, EcsContext context) {
                                fail("closed manager computed");
                                return 0;
                            }
                        });
        manager.tickAll();
        assertTrue(manager.isClosed());
        assertFalse(manager.isTicking());
        assertEquals(0, manager.context().pendingCommands());
    }

    /**
     * An executor that queues work for later cannot make old component batches run twice on reused
     * inputs.
     */
    @Test
    void delayedExecutor_oldChunkTasks_cannotRunAgainOnFollowingTick() {
        try (var manager = new EcsObjectManager()) {
            List<Runnable> delayed = new ArrayList<>();
            manager.scheduler().setExecutor(delayed::add);
            var component = manager.create().attach(new Counter());
            manager.tickAll();
            manager.tickAll();
            for (Runnable task : delayed) task.run();
            assertEquals(2, component.value);
            assertEquals(2, component.captures);
        }
    }

    /**
     * Both execution annotations work without any explicitly registered EcsSystem.
     */
    @Test
    void restrictions_forceOwnerAndIgnoreOptionalBudget() {
        try (var manager = new EcsObjectManager()) {
            manager.scheduler().setOptionalBudget(0);
            var component = manager.create().attach(new MandatoryCounter(Thread.currentThread()));
            manager.tickAll();
            assertEquals(1, component.value);
            assertTrue(component.deadlineObserved);
        }
    }

    /**
     * An optional invocation waits without keeping a stale snapshot, even after its original object
     * interval passes.
     */
    @Test
    void optionalBudget_retainsUncapturedWorkAndCapturesFreshDataLater() {
        try (var manager = new EcsObjectManager()) {
            manager.scheduler().setOptionalBudget(0);
            var object = manager.create();
            object.setTickInterval(10);
            var component = object.attach(new OptionalCounter());
            manager.tickAll();
            assertEquals(0, component.captures);
            component.value = 9;
            manager.scheduler().setOptionalBudget(1);
            manager.tickAll();
            assertEquals(1, component.captures);
            assertEquals(10, component.value);
            manager.tickAll();
            assertEquals(10, component.value);
        }
    }

    /**
     * Removing and reattaching the same component instance invalidates the old result and its
     * queued commands.
     */
    @Test
    void detachAndReattachDuringSiblingApply_discardsStaleAttachmentResult() {
        try (var manager = new EcsObjectManager()) {
            var firstObject = manager.create();
            var secondObject = manager.create();
            var second = new ReattachedCounter();
            var first = firstObject.attach(new ReattachedCounter());
            secondObject.attach(second);
            first.onApply =
                    () -> {
                        secondObject.detachComponent(second);
                        secondObject.attach(second);
                    };
            manager.tickAll();
            assertEquals(1, first.value);
            assertEquals(0, second.value);
            assertEquals(0, second.commands.get());
        }
    }

    /**
     * Capture/compute/apply errors discard only the failing instance's commands, then report an
     * aggregate.
     */
    @Test
    void perInstanceFailures_stillApplyHealthyResultsWithoutPublishingFailedCommands() {
        for (String stage : List.of("capture", "compute", "apply")) {
            try (var manager = new EcsObjectManager()) {
                var broken = manager.create().attach(new FailingCounter(stage));
                var healthy = manager.create().attach(new FailingCounter("none"));
                assertThrows(EcsObjectException.class, manager::tickAll);
                assertEquals(1, healthy.value, stage);
                assertEquals(1, healthy.commands.get(), stage);
                assertEquals(0, broken.commands.get(), stage);
                assertEquals(0, manager.context().pendingCommands(), stage);
                assertFalse(manager.isTicking());
            }
        }
    }

    /**
     * Custom aggregate systems observe committed automatic component changes through ordinary
     * Read/Write ordering.
     */
    @Test
    void explicitSystem_afterAutomaticGroup_seesCommittedComponentState() {
        try (var manager = new EcsObjectManager()) {
            var component = manager.create().attach(new Counter());
            manager.scheduler()
                    .register(
                            new EcsSystem<Integer, Integer>() {
                                @Override
                                public String id() {
                                    return "test:reader";
                                }

                                @Override
                                public EcsAccess access() {
                                    return EcsAccess.NONE.read(Counter.class);
                                }

                                @Override
                                public List<String> after() {
                                    return List.of(
                                            SnapshotTickableComponent.systemId(Counter.class));
                                }

                                @Override
                                public Integer prepare(
                                        EcsContext context, EcsObjectManager owner) {
                                    return component.value;
                                }

                                @Override
                                public Integer compute(Integer input, EcsContext context) {
                                    return input;
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        EcsContext context,
                                        EcsObjectManager owner) {
                                    assertEquals(1, result);
                                }
                            });
            manager.scheduler().validatePlan();
            manager.tickAll();
        }
    }

    /**
     * Late attachments are first observed next tick, so they cannot sneak a second callback into
     * the current phase.
     */
    @Test
    void componentAttachedDuringTick_startsNextTick() {
        try (var manager = new EcsObjectManager()) {
            var object = manager.create();
            var component = new Counter();
            manager.context().enqueue(TickPhase.PRE, () -> object.attach(component));
            manager.tickAll();
            assertEquals(0, component.value);
            manager.tickAll();
            assertEquals(1, component.value);
        }
    }

    /**
     * Old and new callback contracts cannot accidentally tick the same component twice.
     */
    @Test
    void mixedLegacyAndSnapshotInterfaces_areRejectedBeforeAttachment() {
        try (var manager = new EcsObjectManager()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.create().attach(new MixedCounter()));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ComponentType<>(
                                    "test:mixed",
                                    MixedCounter.class,
                                    ComponentType.DuplicatePolicy.REJECT,
                                    MixedCounter::new));
        }
    }

    /**
     * Default executor fallback has the same forbidden live-state access as an actual worker.
     */
    @Test
    void compute_ownerFallbackCannotAccessWorldOrMutateManager() {
        try (var manager = new EcsObjectManager()) {
            manager.scheduler().setExecutor(Runnable::run);
            var component =
                    manager.create()
                            .attach(
                                    new Counter() {
                                        @Override
                                        public Integer compute(
                                                Integer input, EcsContext context) {
                                            assertThrows(
                                                    IllegalStateException.class, context::level);
                                            assertThrows(
                                                    IllegalStateException.class, manager::create);
                                            return super.compute(input, context);
                                        }
                                    });
            manager.tickAll();
            assertEquals(1, component.value);
        }
    }

    /**
     * Minimal component-local snapshot callback with owner-thread state.
     */
    private static class Counter extends Component
            implements SnapshotTickableComponent<Integer, Integer> {
        /**
         * Current owner-thread counter.
         */
        int value;

        /**
         * Number of owner-thread captures.
         */
        int captures;

        /**
         * @param context owner-thread context
         * @return copied scalar value
         */
        @Override
        public Integer capture(EcsContext context) {
            captures++;
            return value;
        }

        /**
         * @param input copied value
         * @param context deferred commands
         * @return next scalar value
         */
        @Override
        public Integer compute(Integer input, EcsContext context) {
            return input + 1;
        }

        /**
         * @param result computed value
         * @param context owner-thread context
         */
        @Override
        public void apply(Integer result, EcsContext context) {
            value = result;
        }
    }

    /**
     * Explicit POST callback verifies scheduling remains consistent after legacy dispatch.
     */
    private static final class PostCounter extends Counter {
        /**
         * @return POST
         */
        @Override
        public TickPhase tickPhase() {
            return TickPhase.POST;
        }
    }

    /**
     * Optional callback used to exercise deferred snapshot preparation.
     */
    private static final class OptionalCounter extends Counter {
        /**
         * @return true to allow pre-capture budget deferral
         */
        @Override
        public boolean mayDeferTick() {
            return true;
        }
    }

    /**
     * Mandatory component-local callback that also requires the owner thread.
     */
    @EcsNoThreadSafe
    @EcsNoTickSkip
    private static final class MandatoryCounter extends Counter {
        /**
         * Expected manager thread.
         */
        private final Thread owner;

        /**
         * Whether inherited mandatory command deadlines were observed.
         */
        boolean deadlineObserved;

        /**
         * @param owner expected manager thread
         */
        MandatoryCounter(Thread owner) {
            this.owner = owner;
        }

        /**
         * @return true, overridden by the annotation's current-tick restriction
         */
        @Override
        public boolean mayDeferTick() {
            return true;
        }

        /**
         * @param input copied state
         * @param context restricted compute context
         * @return incremented state
         */
        @Override
        public Integer compute(Integer input, EcsContext context) {
            assertSame(owner, Thread.currentThread());
            assertThrows(
                    IllegalStateException.class, () -> context.enqueue(TickPhase.PRE, () -> {}));
            deadlineObserved = true;
            return super.compute(input, context);
        }
    }

    /**
     * Traces ordered capture/apply/command publication for instances of one concrete class.
     */
    private static final class OrderedCounter extends Counter {
        /**
         * Instance name used in expected event order.
         */
        private final String name;

        /**
         * Owner-thread event log, never accessed from compute.
         */
        private final List<String> trace;

        /**
         * @param name instance label
         * @param trace owner-thread event log
         */
        OrderedCounter(String name, List<String> trace) {
            this.name = name;
            this.trace = trace;
        }

        /**
         * @param context owner context
         * @return copied scalar
         */
        @Override
        public Integer capture(EcsContext context) {
            trace.add("capture-" + name);
            return super.capture(context);
        }

        /**
         * @param result completed scalar
         * @param context owner context
         */
        @Override
        public void apply(Integer result, EcsContext context) {
            trace.add("apply-" + name);
            super.apply(result, context);
            context.enqueue(TickPhase.POST, () -> trace.add("command-" + name));
        }
    }

    /**
     * Coordinates the first instance of each 64-element worker chunk without sleep-based timing
     * assumptions.
     */
    private static final class OverlappingCounter extends Counter {
        /**
         * Immutable batch position for test coordination.
         */
        private final int index;

        /**
         * Thread-safe test barrier, used only by this concurrency fixture.
         */
        private final CountDownLatch entered;

        /**
         * @param index batch position
         * @param entered worker overlap barrier
         */
        OverlappingCounter(int index, CountDownLatch entered) {
            this.index = index;
            this.entered = entered;
        }

        /**
         * @param input copied scalar
         * @param context restricted context
         * @return incremented scalar
         */
        @Override
        public Integer compute(Integer input, EcsContext context) {
            if (index == 0 || index == 64) {
                entered.countDown();
                try {
                    assertTrue(
                            entered.await(5, TimeUnit.SECONDS),
                            "same-type component jobs did not overlap");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return super.compute(input, context);
        }
    }

    /**
     * Reattachment fixture also queues a command that must be discarded with stale results.
     */
    private static final class ReattachedCounter extends Counter {
        /**
         * Optional owner-thread sibling mutation.
         */
        Runnable onApply;

        /**
         * Commands actually committed for this attachment.
         */
        final AtomicInteger commands = new AtomicInteger();

        /**
         * @param input copied scalar
         * @param context restricted context
         * @return incremented scalar
         */
        @Override
        public Integer compute(Integer input, EcsContext context) {
            context.enqueue(TickPhase.POST, commands::incrementAndGet);
            return super.compute(input, context);
        }

        /**
         * @param result computed scalar
         * @param context owner context
         */
        @Override
        public void apply(Integer result, EcsContext context) {
            super.apply(result, context);
            if (onApply != null) onApply.run();
        }
    }

    /**
     * Failure fixture demonstrates per-instance isolation without a user-defined aggregate system.
     */
    private static final class FailingCounter extends Counter {
        /**
         * Stage at which the fixture deliberately fails.
         */
        private final String stage;

        /**
         * Commands committed successfully for this instance.
         */
        final AtomicInteger commands = new AtomicInteger();

        /**
         * @param stage capture, compute, apply or none
         */
        FailingCounter(String stage) {
            this.stage = stage;
        }

        /**
         * @param context owner context
         * @return copied scalar
         */
        @Override
        public Integer capture(EcsContext context) {
            if (stage.equals("capture")) {
                context.enqueue(TickPhase.POST, commands::incrementAndGet);
                throw new IllegalArgumentException(stage);
            }
            return super.capture(context);
        }

        /**
         * @param input copied scalar
         * @param context restricted context
         * @return incremented scalar
         */
        @Override
        public Integer compute(Integer input, EcsContext context) {
            context.enqueue(TickPhase.POST, commands::incrementAndGet);
            if (stage.equals("compute")) throw new IllegalArgumentException(stage);
            return super.compute(input, context);
        }

        /**
         * @param result computed scalar
         * @param context owner context
         */
        @Override
        public void apply(Integer result, EcsContext context) {
            if (stage.equals("apply")) throw new IllegalArgumentException(stage);
            super.apply(result, context);
        }
    }

    /**
     * Invalid double-callback fixture.
     */
    private static final class MixedCounter extends Counter implements TickableComponent {
        /**
         * @param tick unused tick sequence
         */
        @Override
        public void onTick(long tick) {}
    }

    /**
     * Queues removal before a later component's own published command.
     */
    private static final class CommandRemovingCounter extends Counter {
        /**
         * Owner-thread target removed by a deferred world command, possibly null.
         */
        EcsObject target;

        /**
         * Number of commands that actually executed for this instance.
         */
        final AtomicInteger commands = new AtomicInteger();

        /**
         * @param result calculated state
         * @param context owner-thread command buffer
         */
        @Override
        public void apply(Integer result, EcsContext context) {
            super.apply(result, context);
            if (target != null) context.enqueue(TickPhase.POST, target::detach);
            context.enqueue(TickPhase.POST, commands::incrementAndGet);
        }
    }
}
