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
 * Exercises the actual worker scheduler with deterministic barriers instead of timing-based sleep
 * assertions.
 */
class EcsSchedulerTest {
    /**
     * Commands applied at the start of the phase can introduce restrictions absent from the
     * beginning-of-tick snapshot.
     */
    @Test
    void phaseCommand_attachesRestrictedSubtype_beforeJobAdmission() {
        try (var manager = new WorldObjectManager()) {
            Thread owner = Thread.currentThread();
            AtomicInteger computed = new AtomicInteger();
            manager.scheduler().setOptionalBudget(0);
            manager.worldContext()
                    .enqueue(
                            TickPhase.NORMAL, () -> manager.create().attach(new RestrictedChild()));
            manager.scheduler()
                    .register(
                            new TestSystem("late-restriction", EcsAccess.NONE.read(A.class)) {
                                @Override
                                public boolean mayDefer() {
                                    return true;
                                }

                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    assertSame(owner, Thread.currentThread());
                                    computed.incrementAndGet();
                                    return input;
                                }
                            });
            manager.tickAll();
            assertEquals(1, computed.get());
        }
    }

    /**
     * Stable commit order must not starve later independent systems under a small optional budget.
     */
    @Test
    void optionalBudget_independentPendingJobs_eventuallyAllRun() {
        try (var manager = new WorldObjectManager()) {
            manager.scheduler().setOptionalBudget(1);
            List<String> applied = new ArrayList<>();
            for (String name : List.of("one", "two", "three"))
                manager.scheduler()
                        .register(
                                new TestSystem(name, EcsAccess.NONE) {
                                    @Override
                                    public boolean mayDefer() {
                                        return true;
                                    }

                                    @Override
                                    public void apply(
                                            Integer result,
                                            WorldContext context,
                                            WorldObjectManager owner) {
                                        applied.add(id());
                                    }
                                });
            manager.tickAll();
            manager.tickAll();
            manager.tickAll();
            assertEquals(List.of("one", "two", "three"), applied);
        }
    }

    /**
     * A bad isDue callback cannot leave later nodes using the previous tick's scheduling state.
     */
    @Test
    void schedulingFailure_stillRunsIndependentSystemsAndClosesTick() {
        try (var manager = new WorldObjectManager()) {
            AtomicInteger applied = new AtomicInteger();
            manager.scheduler()
                    .register(
                            new TestSystem("broken-schedule", EcsAccess.NONE) {
                                @Override
                                public boolean isDue(long tick) {
                                    throw new IllegalArgumentException("schedule failed");
                                }
                            });
            manager.scheduler()
                    .register(
                            new TestSystem("healthy", EcsAccess.NONE) {
                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    applied.incrementAndGet();
                                }
                            });
            assertThrows(WorldObjectException.class, manager::tickAll);
            assertEquals(1, applied.get());
            assertFalse(manager.isTicking());
        }
    }

    /**
     * Both annotations are independent, inherited and cached by component descriptors.
     */
    @Test
    void descriptor_inheritedRestrictions_cachesBothFlags() {
        var type =
                new WorldObjectComponentType<>(
                        "test:restricted",
                        RestrictedChild.class,
                        WorldObjectComponentType.DuplicatePolicy.REJECT,
                        RestrictedChild::new);
        assertTrue(type.requiresWorldThread());
        assertTrue(type.requiresCurrentTick());
    }

    /**
     * Read/read is compatible, while a write hazards related concrete and base resources.
     */
    @Test
    void access_polymorphicHazards_preserveReadSharing() {
        assertFalse(
                EcsAccess.NONE
                        .read(A.class)
                        .conflictsWith(EcsAccess.NONE.read(RestrictedChild.class)));
        assertTrue(
                EcsAccess.NONE
                        .write(A.class)
                        .conflictsWith(EcsAccess.NONE.read(RestrictedChild.class)));
        assertTrue(
                EcsAccess.NONE
                        .write(RestrictedChild.class)
                        .conflictsWith(EcsAccess.NONE.write(A.class)));
        assertFalse(EcsAccess.NONE.write(A.class).conflictsWith(EcsAccess.NONE.write(B.class)));
    }

    /**
     * Independent jobs overlap, but their queued commands publish in registration order after both
     * complete.
     */
    @Test
    void parallelJobs_reverseCompletion_commitInStableOrder() {
        var executor = Executors.newFixedThreadPool(2);
        try (var manager = new WorldObjectManager()) {
            manager.scheduler().setExecutor(executor);
            List<String> trace = new ArrayList<>();
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch secondFinished = new CountDownLatch(1);
            manager.scheduler()
                    .register(
                            new TestSystem("first", EcsAccess.NONE) {
                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    entered.countDown();
                                    await(entered);
                                    await(secondFinished);
                                    context.enqueue(
                                            TickPhase.POST, () -> trace.add("first-command"));
                                    return input;
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    trace.add("first-apply");
                                }
                            });
            manager.scheduler()
                    .register(
                            new TestSystem("second", EcsAccess.NONE) {
                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    entered.countDown();
                                    await(entered);
                                    context.enqueue(
                                            TickPhase.POST, () -> trace.add("second-command"));
                                    secondFinished.countDown();
                                    return input;
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    trace.add("second-apply");
                                }
                            });
            manager.tickAll();
            assertEquals(
                    List.of("first-apply", "second-apply", "first-command", "second-command"),
                    trace);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A conflicting successor's input is captured only after its predecessor's component/world
     * result commits.
     */
    @Test
    void writeReadDependency_preparesFreshDataAfterCommit() {
        try (var manager = new WorldObjectManager()) {
            AtomicInteger value = new AtomicInteger();
            manager.scheduler()
                    .register(
                            new TestSystem("write", EcsAccess.NONE.write(A.class)) {
                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    value.set(7);
                                    context.enqueue(TickPhase.NORMAL, () -> value.addAndGet(3));
                                }
                            });
            manager.scheduler()
                    .register(
                            new TestSystem("read", EcsAccess.NONE.read(A.class)) {
                                @Override
                                public Integer prepare(
                                        WorldContext context, WorldObjectManager owner) {
                                    return value.get();
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    assertEquals(10, result);
                                }
                            });
            manager.tickAll();
        }
    }

    /**
     * An actual annotated subtype cannot bypass owner-thread requirements through a broad
     * declaration.
     */
    @Test
    void actualRestrictedSubtype_forcesOwnerAndMandatoryExecution() {
        try (var manager = new WorldObjectManager()) {
            manager.create().attach(new RestrictedChild());
            manager.scheduler().setOptionalBudget(0);
            Thread owner = Thread.currentThread();
            AtomicInteger computed = new AtomicInteger();
            manager.scheduler()
                    .register(
                            new TestSystem("restricted", EcsAccess.NONE.read(A.class)) {
                                @Override
                                public boolean mayDefer() {
                                    return true;
                                }

                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    assertSame(owner, Thread.currentThread());
                                    computed.incrementAndGet();
                                    return input;
                                }
                            });
            manager.tickAll();
            assertEquals(1, computed.get());
        }
    }

    /**
     * A required later-phase consumer promotes its optional earlier-phase prerequisites even with a
     * zero budget.
     */
    @Test
    void mandatoryConsumer_promotesEarlierPhasePredecessor() {
        try (var manager = new WorldObjectManager()) {
            manager.scheduler().setOptionalBudget(0);
            List<String> trace = new ArrayList<>();
            manager.scheduler()
                    .register(
                            new TestSystem("producer", EcsAccess.NONE) {
                                @Override
                                public TickPhase phase() {
                                    return TickPhase.PRE;
                                }

                                @Override
                                public boolean mayDefer() {
                                    return true;
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    trace.add("producer");
                                }
                            });
            manager.scheduler()
                    .register(
                            new TestSystem("consumer", EcsAccess.NONE.read(Restricted.class)) {
                                @Override
                                public TickPhase phase() {
                                    return TickPhase.POST;
                                }

                                @Override
                                public List<String> after() {
                                    return List.of("producer");
                                }

                                @Override
                                public boolean mayDefer() {
                                    return true;
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    trace.add("consumer");
                                }
                            });
            manager.tickAll();
            assertEquals(List.of("producer", "consumer"), trace);
        }
    }

    /**
     * Deferred work keeps no old snapshot, and still runs later even when its original isDue
     * predicate becomes false.
     */
    @Test
    void optionalBudget_defersBeforePrepareAndUsesFreshSnapshot() {
        try (var manager = new WorldObjectManager()) {
            manager.scheduler().setOptionalBudget(0);
            List<Integer> applied = new ArrayList<>();
            manager.scheduler()
                    .register(
                            new TestSystem("optional", EcsAccess.NONE) {
                                @Override
                                public boolean mayDefer() {
                                    return true;
                                }

                                @Override
                                public boolean isDue(long tick) {
                                    return tick == 1;
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    applied.add(result);
                                }
                            });
            manager.tickAll();
            assertTrue(applied.isEmpty());
            manager.scheduler().setOptionalBudget(1);
            manager.tickAll();
            assertEquals(List.of(2), applied);
        }
    }

    /**
     * A delayed executor cannot cause a mandatory job to run twice against later-tick reusable
     * buffers.
     */
    @Test
    void queuedFuture_ownerFallbackRunsExactlyOnceEvenWhenExecutorInvokesLater() {
        try (var manager = new WorldObjectManager()) {
            List<Runnable> delayed = new ArrayList<>();
            manager.scheduler().setExecutor(delayed::add);
            AtomicInteger calls = new AtomicInteger();
            manager.scheduler()
                    .register(
                            new TestSystem("job", EcsAccess.NONE) {
                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    calls.incrementAndGet();
                                    return input;
                                }
                            });
            manager.tickAll();
            manager.tickAll();
            for (Runnable stale : delayed) stale.run();
            assertEquals(2, calls.get());
        }
    }

    /**
     * Fallback compute has the same restricted API as worker compute, including manager structural
     * access.
     */
    @Test
    void compute_deniesLiveWorldAndOwnerFallbackMutation() {
        try (var manager = new WorldObjectManager()) {
            manager.scheduler().setExecutor(Runnable::run);
            manager.scheduler()
                    .register(
                            new TestSystem("restricted-api", EcsAccess.NONE) {
                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    assertThrows(IllegalStateException.class, context::level);
                                    assertThrows(IllegalStateException.class, manager::create);
                                    return input;
                                }
                            });
            manager.tickAll();
            assertEquals(0, manager.size());
        }
    }

    /**
     * Worker commands from unsuccessful computation are discarded, while unrelated systems still
     * commit.
     */
    @Test
    void computeFailure_discardsCommandsAndBlocksDependents() {
        try (var manager = new WorldObjectManager()) {
            AtomicInteger committed = new AtomicInteger();
            manager.scheduler()
                    .register(
                            new TestSystem("broken", EcsAccess.NONE) {
                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    context.enqueue(
                                            TickPhase.POST,
                                            () -> fail("failed job published a command"));
                                    throw new IllegalArgumentException("broken");
                                }
                            });
            manager.scheduler()
                    .register(
                            new TestSystem("dependent", EcsAccess.NONE) {
                                @Override
                                public List<String> after() {
                                    return List.of("broken");
                                }

                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    fail("dependency failed");
                                    return input;
                                }
                            });
            manager.scheduler()
                    .register(
                            new TestSystem("independent", EcsAccess.NONE) {
                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    committed.incrementAndGet();
                                }
                            });
            assertThrows(WorldObjectException.class, manager::tickAll);
            assertEquals(1, committed.get());
            assertEquals(0, manager.worldContext().pendingCommands());
            assertFalse(manager.isTicking());
        }
    }

    /**
     * A failure during owner-thread apply must not publish commands accumulated during
     * prepare/compute/apply.
     */
    @Test
    void applyFailure_discardsWholeUnpublishedCommandBuffer() {
        try (var manager = new WorldObjectManager()) {
            manager.scheduler()
                    .register(
                            new TestSystem("broken-apply", EcsAccess.NONE) {
                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    context.enqueue(
                                            TickPhase.POST, () -> fail("compute command leaked"));
                                    return input;
                                }

                                @Override
                                public void apply(
                                        Integer result,
                                        WorldContext context,
                                        WorldObjectManager owner) {
                                    context.enqueue(
                                            TickPhase.POST, () -> fail("apply command leaked"));
                                    throw new IllegalArgumentException("apply failed");
                                }
                            });
            assertThrows(WorldObjectException.class, manager::tickAll);
            assertEquals(0, manager.worldContext().pendingCommands());
        }
    }

    /**
     * Required component restrictions propagate into commands and reject targeting PRE from NORMAL.
     */
    @Test
    void mandatoryJob_rejectsPassedPhaseInsteadOfSilentlyDeferring() {
        try (var manager = new WorldObjectManager()) {
            manager.scheduler()
                    .register(
                            new TestSystem("required", EcsAccess.NONE.read(Restricted.class)) {
                                @Override
                                public Integer compute(Integer input, WorldContext context) {
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> context.enqueue(TickPhase.PRE, () -> {}));
                                    return input;
                                }
                            });
            manager.tickAll();
        }
    }

    /**
     * Invalid dependency graphs fail before gameplay rather than picking an arbitrary ordering.
     */
    @Test
    void graph_rejectsMissingIdsCyclesAndLaterPhaseDependencies() {
        try (var manager = new WorldObjectManager()) {
            manager.scheduler()
                    .register(
                            new TestSystem("one", EcsAccess.NONE) {
                                @Override
                                public List<String> after() {
                                    return List.of("two");
                                }
                            });
            assertThrows(IllegalStateException.class, manager.scheduler()::validatePlan);
            manager.scheduler()
                    .register(
                            new TestSystem("two", EcsAccess.NONE) {
                                @Override
                                public List<String> after() {
                                    return List.of("one");
                                }
                            });
            assertThrows(IllegalStateException.class, manager.scheduler()::validatePlan);
        }
        try (var manager = new WorldObjectManager()) {
            manager.scheduler()
                    .register(
                            new TestSystem("pre", EcsAccess.NONE) {
                                @Override
                                public TickPhase phase() {
                                    return TickPhase.PRE;
                                }

                                @Override
                                public List<String> after() {
                                    return List.of("post");
                                }
                            });
            manager.scheduler()
                    .register(
                            new TestSystem("post", EcsAccess.NONE) {
                                @Override
                                public TickPhase phase() {
                                    return TickPhase.POST;
                                }
                            });
            assertThrows(IllegalStateException.class, manager.scheduler()::validatePlan);
        }
    }

    /**
     * Closing in a preparation callback cannot leak unpublished buffers or leave the manager
     * permanently ticking.
     */
    @Test
    void closeDuringCallback_releasesBuffersAndManagerTickState() {
        var manager = new WorldObjectManager();
        manager.scheduler()
                .register(
                        new TestSystem("close", EcsAccess.NONE) {
                            @Override
                            public Integer prepare(WorldContext context, WorldObjectManager owner) {
                                context.enqueue(TickPhase.POST, () -> fail("closed world"));
                                owner.close();
                                return 0;
                            }
                        });
        manager.tickAll();
        assertTrue(manager.isClosed());
        assertFalse(manager.isTicking());
        assertEquals(0, manager.worldContext().pendingCommands());
    }

    /**
     * Waits with a bounded timeout so failed parallel scheduling produces an assertion instead of
     * hanging tests.
     *
     * @param latch coordination barrier
     */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "jobs did not overlap");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    /**
     * Ordinary resource used for access declarations.
     */
    private static class A extends AbstractWorldObjectComponent {}

    /**
     * Independent resource.
     */
    private static final class B extends AbstractWorldObjectComponent {}

    /**
     * Strong component restrictions inherited by concrete consumers.
     */
    @EcsNoThreadSafe
    @EcsNoTickSkip
    private static class Restricted extends A {}

    /**
     * Concrete subtype used to verify inherited metadata and broad access declarations.
     */
    private static final class RestrictedChild extends Restricted {}

    /**
     * Minimal reusable system fixture; real tests override only the behavior being exercised.
     */
    private static class TestSystem implements EcsSystem<Integer, Integer> {
        /**
         * Stable fixture id.
         */
        private final String id;

        /**
         * Frozen resource declaration.
         */
        private final EcsAccess access;

        /**
         * @param id fixture id
         * @param access resource declaration
         */
        TestSystem(String id, EcsAccess access) {
            this.id = id;
            this.access = access;
        }

        /**
         * @return fixture id
         */
        @Override
        public String id() {
            return id;
        }

        /**
         * @return fixture access declaration
         */
        @Override
        public EcsAccess access() {
            return access;
        }

        /**
         * @return permission to exercise the real worker path
         */
        @Override
        public boolean parallel() {
            return true;
        }

        /**
         * @param context owner-thread context
         * @param manager live manager
         * @return current tick snapshot
         */
        @Override
        public Integer prepare(WorldContext context, WorldObjectManager manager) {
            return (int) context.tick();
        }

        /**
         * @param input input snapshot
         * @param context deferred commands
         * @return unchanged snapshot
         */
        @Override
        public Integer compute(Integer input, WorldContext context) {
            return input;
        }

        /**
         * @param result computed result
         * @param context world context
         * @param manager live manager
         */
        @Override
        public void apply(Integer result, WorldContext context, WorldObjectManager manager) {}
    }
}
