package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises source ECS contracts and regressions in ownership, callbacks and deferred work.
 */
class WorldObjectTest {
    /**
     * Verifies every duplicate policy against two distinct instances of the same type.
     *
     * @param policy attachment policy being exercised
     */
    @ParameterizedTest
    @EnumSource(WorldObjectComponentType.DuplicatePolicy.class)
    void attach_duplicatePolicies_preserveExpectedOwnership(
            WorldObjectComponentType.DuplicatePolicy policy) {
        WorldObject object = new WorldObject();
        var type = type(policy);
        Counter first = object.attach(new Counter(), type);
        Counter second = new Counter();
        if (policy == WorldObjectComponentType.DuplicatePolicy.REJECT) {
            assertThrows(DuplicateComponentException.class, () -> object.attach(second, type));
            assertFalse(second.isAttached());
            assertSame(first, object.getSingle(type).orElseThrow());
        } else {
            object.attach(second, type);
            assertTrue(second.isAttached());
            assertEquals(
                    policy == WorldObjectComponentType.DuplicatePolicy.REPLACE ? 1 : 2,
                    object.size());
            assertEquals(
                    policy == WorldObjectComponentType.DuplicatePolicy.ALLOW_MULTIPLE,
                    first.isAttached());
        }
    }

    /**
     * Verifies stable priority ordering and explicit resort after priorities change.
     */
    @Test
    void tick_prioritiesAndResort_useStableAttachOrder() {
        WorldObject object = new WorldObject();
        List<String> calls = new ArrayList<>();
        Counter first = object.attach(new Counter("first", 5, calls));
        object.attach(new Counter("second", 0, calls));
        object.attach(new Counter("third", 5, calls));
        object.tick(1);
        assertEquals(List.of("second", "first", "third"), calls);
        calls.clear();
        first.order = -1;
        assertTrue(object.resort());
        assertFalse(object.resort());
        object.tick(2);
        assertEquals(List.of("first", "second", "third"), calls);
    }

    /**
     * New components become visible to their callback but join ticking on the next snapshot.
     */
    @Test
    void tick_structuralMutation_preservesCurrentSnapshot() {
        WorldObject object = new WorldObject();
        Counter added = new Counter();
        object.attach(
                new Counter() {
                    @Override
                    public void onTick(long tick) {
                        if (!added.isAttached()) object.attach(added);
                    }
                });
        object.tick(1);
        assertTrue(added.isAttached());
        assertEquals(0, added.ticks);
        object.tick(2);
        assertEquals(1, added.ticks);
    }

    /**
     * Callback failures remain observable without hiding successfully attached components.
     */
    @Test
    void attach_throwingCallback_commitsMembershipAndNotifiesListeners() {
        WorldObject object = new WorldObject();
        AtomicInteger notifications = new AtomicInteger();
        object.addListener(
                new WorldObjectListener() {
                    @Override
                    public void onComponentAdded(
                            WorldObject owner, WorldObjectComponent component) {
                        notifications.incrementAndGet();
                    }
                });
        Counter broken =
                new Counter() {
                    @Override
                    public void onAttached() {
                        assertSame(object, owner().orElseThrow());
                        assertTrue(object.has(getClass()));
                        throw new IllegalStateException("fixture");
                    }
                };
        assertThrows(WorldObjectException.class, () -> object.attach(broken));
        assertTrue(broken.isAttached());
        assertEquals(1, notifications.get());
    }

    /**
     * Tick failures and deferred failures must not suppress healthy components or queued work.
     */
    @Test
    void tick_failingComponentAndDeferredTask_runsRemainingWork() {
        WorldObject object = new WorldObject();
        object.attach(
                new Counter() {
                    @Override
                    public void onTick(long tick) {
                        throw new IllegalStateException("tick");
                    }
                });
        Counter healthy = object.attach(new Counter());
        AtomicInteger work = new AtomicInteger();
        object.defer(
                () -> {
                    throw new IllegalStateException("deferred");
                });
        object.defer(work::incrementAndGet);
        WorldObject.TickException error =
                assertThrows(WorldObject.TickException.class, () -> object.tick(8));
        assertEquals(1, healthy.ticks);
        assertEquals(1, work.get());
        assertEquals(2, error.getSuppressed().length);
    }

    /**
     * Detached owners discard pending work and permanently reject mutations.
     */
    @Test
    void detach_pendingWork_clearsOwnersAndRejectsReattachment() {
        WorldObject object = new WorldObject();
        Counter component = object.attach(new Counter());
        object.defer(() -> fail("Detached work must not run"));
        assertTrue(object.detach());
        assertFalse(object.detach());
        assertFalse(component.isAttached());
        assertTrue(component.owner().isEmpty());
        assertFalse(object.hasPendingDeferred());
        assertThrows(DetachedException.class, () -> object.defer(() -> {}));
        assertThrows(DetachedException.class, () -> object.attach(new Counter()));
    }

    /**
     * Exact and typed removal leave the previously published entries list intact.
     */
    @Test
    void detachComponent_andDetachAll_preserveOldSnapshot() {
        WorldObject object = new WorldObject();
        var type = type(WorldObjectComponentType.DuplicatePolicy.ALLOW_MULTIPLE);
        Counter first = object.attach(new Counter(), type);
        Counter second = object.attach(new Counter(), type);
        var snapshot = object.entries();
        assertTrue(object.detachComponent(first));
        assertFalse(object.detachComponent(first));
        assertEquals(List.of(second), object.detachAll(type));
        assertEquals(2, snapshot.size());
        assertEquals(0, object.size());
        assertFalse(first.isAttached());
        assertFalse(second.isAttached());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    /**
     * Simultaneous attachment to different owners must yield exactly one owner.
     *
     * @throws Exception if concurrent fixture execution fails or times out
     */
    @Test
    void attach_sameComponentFromTwoThreads_hasSingleOwner() throws Exception {
        Counter component = new Counter();
        WorldObject first = new WorldObject();
        WorldObject second = new WorldObject();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> tryAttach(first, component, start));
            var two = executor.submit(() -> tryAttach(second, component, start));
            start.countDown();
            assertEquals(1, one.get(5, TimeUnit.SECONDS) + two.get(5, TimeUnit.SECONDS));
            assertEquals(1, first.size() + second.size());
            assertSame(first.size() == 1 ? first : second, component.owner().orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Objects with the same UUID retain stable identity regardless of membership.
     */
    @Test
    void equals_matchingUuid_preservesIdentity() {
        UUID id = UUID.randomUUID();
        assertEquals(new WorldObject(id), new WorldObject(id));
        assertEquals(new WorldObject(id).hashCode(), new WorldObject(id).hashCode());
        assertNotEquals(new WorldObject(id), new WorldObject());
    }

    /**
     * Attempts one concurrent ownership claim.
     *
     * @param object target owner
     * @param component shared component instance
     * @param start gate coordinating the two attempts
     * @return one for successful attachment, zero for an ownership conflict
     * @throws InterruptedException if the gate is interrupted
     */
    private static int tryAttach(WorldObject object, Counter component, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            object.attach(component);
            return 1;
        } catch (IllegalStateException conflict) {
            return 0;
        }
    }

    /**
     * Creates a fresh descriptor for each duplicate-policy scenario.
     *
     * @param policy duplicate handling policy
     * @return non-null counter descriptor
     */
    private static WorldObjectComponentType<Counter> type(
            WorldObjectComponentType.DuplicatePolicy policy) {
        return new WorldObjectComponentType<>("test:counter", Counter.class, policy, Counter::new);
    }

    /**
     * Tickable fixture exposing priority and callback order without Minecraft dependencies.
     */
    private static class Counter extends AbstractWorldObjectComponent implements TickableComponent {
        /**
         * Number of received ticks, initially zero.
         */
        int ticks;

        /**
         * Mutable priority used by explicit resort tests.
         */
        int order;

        /**
         * Optional callback sequence destination.
         */
        final List<String> calls;

        /**
         * Name stored in the callback sequence.
         */
        final String name;

        /**
         * Creates an unnamed counter with default priority.
         */
        Counter() {
            this("counter", 0, null);
        }

        /**
         * Creates a priority/order fixture.
         *
         * @param name entry name
         * @param order tick priority
         * @param calls optional sequence destination
         */
        Counter(String name, int order, List<String> calls) {
            this.name = name;
            this.order = order;
            this.calls = calls;
        }

        /**
         * Returns the mutable test priority.
         *
         * @return current priority
         */
        @Override
        public int priority() {
            return order;
        }

        /**
         * Counts a tick and records callback order.
         *
         * @param tick manager tick number
         */
        @Override
        public void onTick(long tick) {
            ticks++;
            if (calls != null) calls.add(name);
        }
    }
}
