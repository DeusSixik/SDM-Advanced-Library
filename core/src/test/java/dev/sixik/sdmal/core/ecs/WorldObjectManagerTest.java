package dev.sixik.sdmal.core.ecs;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies manager membership, callback cleanup and permanent closure. */
class WorldObjectManagerTest {
    /** Duplicate UUIDs must not overwrite the existing object or detach the rejected argument. */
    @Test void add_duplicateUuid_preservesExistingObject() {
        WorldObjectManager manager = new WorldObjectManager();
        UUID id = UUID.randomUUID();
        WorldObject first = new WorldObject(id);
        WorldObject duplicate = new WorldObject(id);
        assertTrue(manager.add(first));
        assertFalse(manager.add(duplicate));
        assertSame(first, manager.get(id).orElseThrow());
        assertFalse(duplicate.isDetached());
        assertThrows(UnsupportedOperationException.class, () -> manager.all().clear());
        manager.close();
    }

    /** Every object receives the same monotonic tick number and failing objects do not stop others. */
    @Test void tickAll_failingObject_ticksRemainingObjects() {
        WorldObjectManager manager = new WorldObjectManager();
        manager.create().attach(new AbstractWorldObjectComponent() {});
        AtomicInteger ticks = new AtomicInteger();
        manager.create().attach(new Counter(ticks, true));
        manager.create().attach(new Counter(ticks, false));
        assertThrows(WorldObjectException.class, manager::tickAll);
        assertEquals(2, ticks.get());
        assertEquals(1, manager.lastTick());
        manager.close();
        manager.tickAll();
        assertEquals(1, manager.lastTick());
    }

    /** Repeated world ticks advance the manager sequence and tick each live object once. */
    @Test void tickAll_repeatedWorldTicks_advanceSequence() {
        WorldObjectManager manager = new WorldObjectManager();
        AtomicInteger ticks = new AtomicInteger();
        manager.create().attach(new Counter(ticks, false));
        manager.tickAll();
        manager.tickAll();
        assertEquals(2, ticks.get());
        assertEquals(2, manager.lastTick());
        manager.close();
    }

    /** Close releases every object even when a removal listener throws. */
    @Test void close_throwingListener_detachesEverythingAndRejectsNewWork() {
        WorldObjectManager manager = new WorldObjectManager();
        WorldObject one = manager.create();
        WorldObject two = manager.create();
        AtomicInteger removed = new AtomicInteger();
        manager.addListener(new WorldObjectManager.Listener() {
            @Override public void onObjectRemoved(WorldObjectManager owner, WorldObject object) {
                assertTrue(owner.isClosed());
                throw new IllegalStateException("fixture");
            }
        });
        manager.addListener(new WorldObjectManager.Listener() {
            @Override public void onObjectRemoved(WorldObjectManager owner, WorldObject object) { removed.incrementAndGet(); }
        });
        assertThrows(WorldObjectException.class, manager::close);
        assertEquals(2, removed.get());
        assertTrue(one.isDetached());
        assertTrue(two.isDetached());
        assertEquals(0, manager.size());
        assertThrows(IllegalStateException.class, manager::create);
        assertThrows(IllegalStateException.class, () -> manager.add(new WorldObject()));
        assertDoesNotThrow(manager::close);
    }

    /** Managers reject already detached objects and detach objects they remove. */
    @Test void remove_registeredObject_detachesAndUnregisters() {
        WorldObjectManager manager = new WorldObjectManager();
        WorldObject object = manager.create();
        assertTrue(manager.remove(object.id()));
        assertTrue(object.isDetached());
        assertFalse(manager.remove(object.id()));
        assertThrows(IllegalStateException.class, () -> manager.add(object));
        manager.close();
    }

    /** A descriptor's registration is idempotent only for the same descriptor instance. */
    @Test void registry_duplicateId_rejectsDifferentDescriptor() {
        var registry = WorldObjectComponentRegistry.instance();
        String id = "test:" + UUID.randomUUID();
        var first = new WorldObjectComponentType<>(id, Counter.class,
            WorldObjectComponentType.DuplicatePolicy.REJECT, () -> new Counter(new AtomicInteger(), false));
        var second = new WorldObjectComponentType<>(id, Counter.class,
            WorldObjectComponentType.DuplicatePolicy.REJECT, () -> new Counter(new AtomicInteger(), false));
        try {
            assertSame(first, registry.register(first));
            assertSame(first, registry.register(first));
            assertThrows(IllegalStateException.class, () -> registry.register(second));
            assertSame(first, registry.get(id, Counter.class).orElseThrow());
            assertFalse(first.createDefault().isAttached());
        } finally { registry.unregister(id); }
        assertTrue(registry.get(id).isEmpty());
    }

    /** Component fixture optionally failing each tick after incrementing a shared counter. */
    private static final class Counter extends AbstractWorldObjectComponent implements TickableComponent {
        /** Shared tick invocation count. */
        final AtomicInteger ticks;
        /** Whether this component fails its tick. */
        final boolean fail;
        /**
         * Configures tick counting and failure.
         * @param ticks shared counter, non-null
         * @param fail whether to throw after counting
         */
        Counter(AtomicInteger ticks, boolean fail) { this.ticks = ticks; this.fail = fail; }
        /**
         * Counts one tick and optionally fails.
         * @param tick manager tick number
         * @throws IllegalStateException for the failing fixture
         */
        @Override public void onTick(long tick) { ticks.incrementAndGet(); if (fail) throw new IllegalStateException("fixture"); }
    }
}
