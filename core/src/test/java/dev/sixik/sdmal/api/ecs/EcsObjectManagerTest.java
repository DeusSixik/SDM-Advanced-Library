package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies manager membership, callback cleanup and permanent closure.
 */
class EcsObjectManagerTest {
    /**
     * Duplicate UUIDs must not overwrite the existing object or detach the rejected argument.
     */
    @Test
    void add_duplicateUuid_preservesExistingObject() {
        EcsObjectManager manager = new EcsObjectManager();
        UUID id = UUID.randomUUID();
        EcsObject first = new EcsObject(id);
        EcsObject duplicate = new EcsObject(id);
        assertTrue(manager.add(first));
        assertFalse(manager.add(duplicate));
        assertSame(first, manager.get(id).orElseThrow());
        assertFalse(duplicate.isDetached());
        assertThrows(UnsupportedOperationException.class, () -> manager.all().clear());
        manager.close();
    }

    /**
     * Every object receives the same monotonic tick number and failing objects do not stop others.
     */
    @Test
    void tickAll_failingObject_ticksRemainingObjects() {
        EcsObjectManager manager = new EcsObjectManager();
        manager.create().attach(new Component() {});
        AtomicInteger ticks = new AtomicInteger();
        manager.create().attach(new Counter(ticks, true));
        manager.create().attach(new Counter(ticks, false));
        assertThrows(EcsObjectException.class, manager::tickAll);
        assertEquals(2, ticks.get());
        assertEquals(1, manager.lastTick());
        manager.close();
        manager.tickAll();
        assertEquals(1, manager.lastTick());
    }

    /**
     * Repeated world ticks advance the manager sequence and tick each live object once.
     */
    @Test
    void tickAll_repeatedWorldTicks_advanceSequence() {
        EcsObjectManager manager = new EcsObjectManager();
        AtomicInteger ticks = new AtomicInteger();
        manager.create().attach(new Counter(ticks, false));
        manager.tickAll();
        manager.tickAll();
        assertEquals(2, ticks.get());
        assertEquals(2, manager.lastTick());
        manager.close();
    }

    /**
     * Close releases every object even when a removal listener throws.
     */
    @Test
    void close_throwingListener_detachesEverythingAndRejectsNewWork() {
        EcsObjectManager manager = new EcsObjectManager();
        EcsObject one = manager.create();
        EcsObject two = manager.create();
        AtomicInteger removed = new AtomicInteger();
        manager.addListener(
                new EcsObjectManager.Listener() {
                    @Override
                    public void onObjectRemoved(EcsObjectManager owner, EcsObject object) {
                        assertTrue(owner.isClosed());
                        throw new IllegalStateException("fixture");
                    }
                });
        manager.addListener(
                new EcsObjectManager.Listener() {
                    @Override
                    public void onObjectRemoved(EcsObjectManager owner, EcsObject object) {
                        removed.incrementAndGet();
                    }
                });
        assertThrows(EcsObjectException.class, manager::close);
        assertEquals(2, removed.get());
        assertTrue(one.isDetached());
        assertTrue(two.isDetached());
        assertEquals(0, manager.size());
        assertThrows(IllegalStateException.class, manager::create);
        assertThrows(IllegalStateException.class, () -> manager.add(new EcsObject()));
        assertDoesNotThrow(manager::close);
    }

    /**
     * Managers reject already detached objects and detach objects they remove.
     */
    @Test
    void remove_registeredObject_detachesAndUnregisters() {
        EcsObjectManager manager = new EcsObjectManager();
        EcsObject object = manager.create();
        assertTrue(manager.remove(object.id()));
        assertTrue(object.isDetached());
        assertFalse(manager.remove(object.id()));
        assertThrows(IllegalStateException.class, () -> manager.add(object));
        manager.close();
    }

    /**
     * A descriptor's registration is idempotent only for the same descriptor instance.
     */
    @Test
    void registry_duplicateId_rejectsDifferentDescriptor() {
        var registry = ComponentRegistry.instance();
        String id = "test:" + UUID.randomUUID();
        var first =
                new ComponentType<>(
                        id,
                        Counter.class,
                        ComponentType.DuplicatePolicy.REJECT,
                        () -> new Counter(new AtomicInteger(), false));
        var second =
                new ComponentType<>(
                        id,
                        Counter.class,
                        ComponentType.DuplicatePolicy.REJECT,
                        () -> new Counter(new AtomicInteger(), false));
        try {
            assertSame(first, registry.register(first));
            assertSame(first, registry.register(first));
            assertThrows(IllegalStateException.class, () -> registry.register(second));
            assertSame(first, registry.get(id, Counter.class).orElseThrow());
            assertFalse(first.createDefault().isAttached());
        } finally {
            registry.unregister(id);
        }
        assertTrue(registry.get(id).isEmpty());
    }

    /**
     * Component fixture optionally failing each tick after incrementing a shared counter.
     */
    private static final class Counter extends Component
            implements TickableComponent {
        /**
         * Shared tick invocation count.
         */
        final AtomicInteger ticks;

        /**
         * Whether this component fails its tick.
         */
        final boolean fail;

        /**
         * Configures tick counting and failure.
         *
         * @param ticks shared counter, non-null
         * @param fail whether to throw after counting
         */
        Counter(AtomicInteger ticks, boolean fail) {
            this.ticks = ticks;
            this.fail = fail;
        }

        /**
         * Counts one tick and optionally fails.
         *
         * @param tick manager tick number
         * @throws IllegalStateException for the failing fixture
         */
        @Override
        public void onTick(long tick) {
            ticks.incrementAndGet();
            if (fail) throw new IllegalStateException("fixture");
        }
    }
}
