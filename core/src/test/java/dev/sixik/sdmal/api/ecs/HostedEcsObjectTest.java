package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.api.ecs.sync.NetworkComponent;
import dev.sixik.sdmal.api.ecs.sync.TrackedField;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Verifies host-owned persistence, exact membership and transactional binary decoding.
 */
class HostedEcsObjectTest {
    /**
     * Fixture registrations removed after each case.
     */
    private final List<String> ids = new ArrayList<>();

    /**
     * Initializes real Minecraft serialization registries.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Removes fixture types without disturbing library service registrations.
     */
    @AfterEach
    void cleanup() {
        ids.forEach(id -> ComponentRegistry.instance().unregister(id));
    }

    /**
     * Chunk lifecycle retains identity/state without duplicating data in the world's archive.
     */
    @Test
    void bind_saveUnloadReload_preservesIdentityAndSinglePersistenceOwner() {
        var type = type(Counter.class, Counter::new);
        try (var manager = new EcsObjectManager()) {
            var host = new HostedEcsObject();
            assertTrue(host.object().isEmpty());
            assertTrue(host.bind(manager, o -> o.attach(new Counter(), type), o -> {}));
            var old = host.object().orElseThrow();
            manager.tickAll();
            assertEquals(1, old.getSingle(type).orElseThrow().value);
            assertFalse(old.isPersistent());
            assertThrows(IllegalStateException.class, () -> old.setPersistent(true));
            assertEquals(0, ((ListTag) manager.writeNbt().get("objects")).size());
            var snapshot = host.save();
            snapshot.putString("external", "must not alias");
            assertNull(host.save().get("external"));
            host.unbind();
            assertTrue(old.isDetached());
            assertEquals(0, manager.size());
            host.unbind();
            manager.tickAll();
            assertTrue(
                    host.bind(manager, o -> fail("Saved object must not run defaults"), o -> {}));
            var restored = host.object().orElseThrow();
            assertEquals(old.id(), restored.id());
            assertNotSame(old, restored);
            assertEquals(1, restored.getSingle(type).orElseThrow().value);
            manager.tickAll();
            assertEquals(2, restored.getSingle(type).orElseThrow().value);
            host.unbind();
        }
    }

    /**
     * A second handle restores chunk-owned NBT after the original Minecraft shell is discarded.
     */
    @Test
    void load_diskCopy_doesNotRetainCallerTags() {
        var type = type(Counter.class, Counter::new);
        try (var manager = new EcsObjectManager()) {
            var first = new HostedEcsObject();
            first.bind(manager, o -> o.attach(new Counter(), type).value = 67, o -> {});
            var id = first.object().orElseThrow().id();
            var disk = first.save();
            first.unbind();
            var second = new HostedEcsObject();
            second.load(disk);
            disk.putInt("format", 999);
            assertTrue(second.bind(manager, o -> fail(), o -> {}));
            assertEquals(id, second.object().orElseThrow().id());
            assertEquals(67, second.object().orElseThrow().getSingle(type).orElseThrow().value);
            second.unbind();
        }
    }

    /**
     * Unknown types quarantine the whole composition and survive saving unchanged.
     */
    @Test
    void bind_missingComponent_preservesOpaqueArchiveAndCanRetry() {
        var type = type(Counter.class, Counter::new);
        try (var manager = new EcsObjectManager()) {
            var host = new HostedEcsObject();
            host.bind(manager, o -> o.attach(new Counter(), type), o -> {});
            var disk = host.save();
            host.unbind();
            ComponentRegistry.instance().unregister(type.id());
            assertFalse(host.bind(manager, o -> fail(), o -> fail()));
            assertTrue(host.restoreFailure().orElseThrow().contains(type.id()));
            assertEquals(disk, host.save());
            assertEquals(0, manager.size());
            ComponentRegistry.instance().register(type);
            assertTrue(host.bind(manager, o -> fail(), o -> {}));
            assertTrue(host.restoreFailure().isEmpty());
            host.unbind();
        }
    }

    /**
     * Future archive formats are never overwritten by new defaults.
     */
    @Test
    void bind_futureFormat_preservesUnknownData() {
        var disk = new CompoundTag();
        disk.putInt("format", 999);
        disk.put("objects", new ListTag());
        disk.putString("future", "opaque payload");
        var host = new HostedEcsObject();
        host.load(disk);
        try (var manager = new EcsObjectManager()) {
            assertFalse(host.bind(manager, o -> fail(), o -> fail()));
            assertEquals(disk, host.save());
            assertTrue(host.restoreFailure().isPresent());
        }
    }

    /**
     * Runtime-only state is reconstructed before restored attachment callbacks.
     */
    @Test
    void bind_restore_runsRuntimeSetupBeforeAttachmentCallbacks() {
        var type = type(Probe.class, Probe::new);
        try (var manager = new EcsObjectManager()) {
            var host = new HostedEcsObject();
            host.bind(
                    manager,
                    o -> o.attach(new Probe(), type),
                    o -> o.getSingle(type).orElseThrow().runtime = true);
            host.unbind();
            host.bind(manager, o -> fail(), o -> o.getSingle(type).orElseThrow().runtime = true);
            var restored = host.object().orElseThrow().getSingle(type).orElseThrow();
            assertTrue(restored.observedRuntime);
            assertTrue(restored.observedManager);
            host.unbind();
        }
    }

    /**
     * Failed unpublished setup cleans ownership and permits another attempt.
     */
    @Test
    void bind_runtimeFailure_doesNotPublishPartialObject() {
        var type = type(Counter.class, Counter::new);
        try (var manager = new EcsObjectManager()) {
            var host = new HostedEcsObject();
            var component = new Counter();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            host.bind(
                                    manager,
                                    o -> o.attach(component, type),
                                    o -> {
                                        throw new IllegalStateException("fixture");
                                    }));
            assertFalse(component.isAttached());
            assertTrue(host.object().isEmpty());
            assertEquals(0, manager.size());
            assertTrue(host.bind(manager, o -> o.attach(new Counter(), type), o -> {}));
            host.unbind();
        }
    }

    /**
     * A duplicate UUID must not detach or replace its existing owner.
     */
    @Test
    void bind_duplicateId_keepsOriginalMembership() {
        var type = type(Counter.class, Counter::new);
        try (var manager = new EcsObjectManager()) {
            var first = new HostedEcsObject();
            first.bind(manager, o -> o.attach(new Counter(), type), o -> {});
            var second = new HostedEcsObject();
            second.load(first.save());
            assertThrows(
                    IllegalStateException.class, () -> second.bind(manager, o -> fail(), o -> {}));
            assertEquals(1, manager.size());
            assertFalse(first.object().orElseThrow().isDetached());
            assertTrue(second.object().isEmpty());
            first.unbind();
        }
    }

    /**
     * Non-fatal detach callbacks still release the handle and retain its last complete snapshot.
     */
    @Test
    void unbind_failingDetach_retainsSavedStateAndReleasesMembership() {
        var type = type(BadDetach.class, BadDetach::new);
        try (var manager = new EcsObjectManager()) {
            var host = new HostedEcsObject();
            host.bind(manager, o -> o.attach(new BadDetach(), type), o -> {});
            var expected = host.save();
            assertThrows(EcsObjectException.class, host::unbind);
            assertEquals(0, manager.size());
            assertTrue(host.object().isEmpty());
            assertEquals(expected, host.save());
        }
    }

    /**
     * Lifecycle operations reject worker access even when no manager has been assigned yet.
     *
     * @throws Exception if the worker does not finish
     */
    @Test
    void save_foreignThread_rejectsAccess() throws Exception {
        var host = new HostedEcsObject();
        CompletableFuture.supplyAsync(() -> assertThrows(IllegalStateException.class, host::save))
                .get(5, TimeUnit.SECONDS);
    }

    /**
     * Full binary frames synchronize composition and selected fields, excluding disk-only values.
     */
    @Test
    void network_roundTrip_excludesPrivatePersistedFields() {
        var type = type(Counter.class, Counter::new);
        try (var server = new EcsObjectManager();
                var client = new EcsObjectManager()) {
            var source = new HostedEcsObject();
            source.bind(
                    server,
                    o -> {
                        var counter = o.attach(new Counter(), type);
                        counter.value = 42;
                        counter.secret = 783;
                    },
                    o -> {});
            var target = new HostedEcsObject();
            target.bind(client, o -> o.attach(new Counter(), type), o -> {});
            var old = target.object().orElseThrow();
            FriendlyByteBuf buffer = buffer();
            try {
                source.writeNetwork(buffer);
                target.readNetwork(buffer, o -> o.setTickCondition(() -> false));
                assertFalse(buffer.isReadable());
                var mirror = target.object().orElseThrow();
                assertEquals(source.object().orElseThrow().id(), mirror.id());
                assertTrue(old.isDetached());
                assertEquals(42, mirror.getSingle(type).orElseThrow().value);
                assertEquals(0, mirror.getSingle(type).orElseThrow().secret);
                client.tickAll();
                assertEquals(42, mirror.getSingle(type).orElseThrow().value);
            } finally {
                buffer.release();
                source.unbind();
                target.unbind();
            }
        }
    }

    /**
     * Repeated types, stable attachment sequences and stateless/tracked wire modes round-trip.
     */
    @Test
    void network_repeatedAndTrackedComponents_preservesOrderAndValues() {
        var type =
                type(
                        Tracked.class,
                        Tracked::new,
                        ComponentType.DuplicatePolicy.ALLOW_MULTIPLE);
        var plain = type(Plain.class, Plain::new);
        try (var server = new EcsObjectManager();
                var client = new EcsObjectManager()) {
            var source = new HostedEcsObject();
            source.bind(
                    server,
                    o -> {
                        o.attach(new Tracked(), type).value.set(7);
                        o.attach(new Tracked(), type).value.set(91);
                        o.attach(new Plain(), plain);
                    },
                    o -> {});
            var target = new HostedEcsObject();
            target.bind(client, o -> {}, o -> {});
            var buffer = buffer();
            try {
                source.writeNetwork(buffer);
                target.readNetwork(buffer, o -> {});
                var mirror = target.object().orElseThrow();
                assertEquals(
                        List.of(7, 91), mirror.get(type).stream().map(c -> c.value.get()).toList());
                assertTrue(mirror.has(plain));
                assertEquals(
                        source.object().orElseThrow().entries().stream()
                                .map(EcsObject.ComponentEntry::sequence)
                                .toList(),
                        mirror.entries().stream()
                                .map(EcsObject.ComponentEntry::sequence)
                                .toList());
            } finally {
                buffer.release();
                source.unbind();
                target.unbind();
            }
        }
    }

    /**
     * Every truncated prefix fails before changing the live client composition or consuming input.
     */
    @Test
    void network_truncatedFrame_keepsPreviousCompositionAndReaderIndex() {
        var type = type(Counter.class, Counter::new);
        try (var server = new EcsObjectManager();
                var client = new EcsObjectManager()) {
            var source = new HostedEcsObject();
            source.bind(server, o -> o.attach(new Counter(), type), o -> {});
            var target = new HostedEcsObject();
            target.bind(client, o -> o.attach(new Counter(), type), o -> {});
            var before = target.object().orElseThrow();
            var buffer = buffer();
            try {
                source.writeNetwork(buffer);
                for (int length = 0; length < buffer.writerIndex(); length++) {
                    var prefix = new FriendlyByteBuf(buffer.slice(0, length));
                    assertThrows(RuntimeException.class, () -> target.readNetwork(prefix, o -> {}));
                    assertEquals(0, prefix.readerIndex());
                    assertSame(before, target.object().orElseThrow());
                }
                assertEquals(1, client.size());
            } finally {
                buffer.release();
                source.unbind();
                target.unbind();
            }
        }
    }

    /**
     * Unsupported formats, modes, unknown types and trailing bytes are atomic decode errors.
     */
    @Test
    void network_malformedFrames_rejectsBeforeReplacingObject() {
        var type = type(Counter.class, Counter::new);
        try (var client = new EcsObjectManager()) {
            var target = new HostedEcsObject();
            target.bind(client, o -> o.attach(new Counter(), type), o -> {});
            var before = target.object().orElseThrow();
            for (int problem = 0; problem < 6; problem++) {
                var buffer = buffer();
                try {
                    buffer.writeInt(0);
                    buffer.writeVarInt(problem == 0 ? 99 : 1);
                    buffer.writeUUID(UUID.randomUUID());
                    buffer.writeVarInt(1);
                    buffer.writeUtf(problem == 1 ? "missing:type" : type.id());
                    buffer.writeVarLong(problem == 2 ? -1 : 0);
                    buffer.writeByte(problem == 3 ? 0 : 1);
                    new Counter().writeNetwork(buffer);
                    if (problem == 4) buffer.writeByte(99);
                    buffer.setInt(0, problem == 5 ? Integer.MAX_VALUE : buffer.writerIndex() - 4);
                    assertThrows(RuntimeException.class, () -> target.readNetwork(buffer, o -> {}));
                    assertSame(before, target.object().orElseThrow());
                    assertEquals(0, buffer.readerIndex());
                } finally {
                    buffer.release();
                }
            }
            target.unbind();
        }
    }

    /**
     * Replacing a mirror invalidates its old deferred callbacks even when its UUID stays the same.
     */
    @Test
    void network_sameIdReplacement_discardsOldDeferredWork() {
        var type = type(Counter.class, Counter::new);
        try (var server = new EcsObjectManager();
                var client = new EcsObjectManager()) {
            var source = new HostedEcsObject();
            source.bind(server, o -> o.attach(new Counter(), type), o -> {});
            var target = new HostedEcsObject();
            target.load(source.save());
            target.bind(client, o -> fail(), o -> {});
            var previous = target.object().orElseThrow();
            previous.defer(() -> fail("Old attachment work must be discarded"));
            var buffer = buffer();
            try {
                source.writeNetwork(buffer);
                target.readNetwork(buffer, o -> {});
                client.tickAll();
                assertTrue(previous.isDetached());
                assertEquals(1, target.object().orElseThrow().getSingle(type).orElseThrow().value);
            } finally {
                buffer.release();
                source.unbind();
                target.unbind();
            }
        }
    }

    /**
     * Commit callback failures are reported after completing the replacement and retaining a usable
     * handle.
     */
    @Test
    void network_detachCallbackFailure_stillInstallsNewComposition() {
        var type = type(BadDetach.class, BadDetach::new);
        try (var server = new EcsObjectManager();
                var client = new EcsObjectManager()) {
            var source = new HostedEcsObject();
            source.bind(server, o -> o.attach(new BadDetach(), type), o -> {});
            var target = new HostedEcsObject();
            target.bind(client, o -> o.attach(new BadDetach(), type), o -> {});
            var previous = target.object().orElseThrow();
            var buffer = buffer();
            try {
                source.writeNetwork(buffer);
                assertThrows(EcsObjectException.class, () -> target.readNetwork(buffer, o -> {}));
                assertTrue(previous.isDetached());
                assertEquals(
                        source.object().orElseThrow().id(), target.object().orElseThrow().id());
                assertDoesNotThrow(target::save);
                assertEquals(1, client.size());
            } finally {
                buffer.release();
                assertThrows(EcsObjectException.class, source::unbind);
                assertThrows(EcsObjectException.class, target::unbind);
            }
        }
    }

    /**
     * Encoding failure restores the caller's writer index.
     */
    @Test
    void network_unregisteredComposition_rollsBackWriterIndex() {
        try (var manager = new EcsObjectManager()) {
            var host = new HostedEcsObject();
            host.bind(manager, o -> o.attach(new Plain()), o -> {});
            var buffer = buffer();
            try {
                buffer.writeByte(37);
                assertThrows(EcsObjectException.class, () -> host.writeNetwork(buffer));
                assertEquals(1, buffer.writerIndex());
                assertEquals(37, buffer.getByte(0));
            } finally {
                buffer.release();
            }
        }
    }

    /**
     * Registers a non-repeatable fixture.
     *
     * @param cls component class
     * @param factory fresh detached instances
     * @param <C> component type
     * @return registered fixture type
     */
    private <C extends Component> ComponentType<C> type(
            Class<C> cls, Supplier<C> factory) {
        return type(cls, factory, ComponentType.DuplicatePolicy.REJECT);
    }

    /**
     * Registers a fixture with explicit duplicate semantics.
     *
     * @param cls component class
     * @param factory fresh detached instances
     * @param policy duplicate policy
     * @param <C> component type
     * @return registered fixture type
     */
    private <C extends Component> ComponentType<C> type(
            Class<C> cls, Supplier<C> factory, ComponentType.DuplicatePolicy policy) {
        String id = "test:" + UUID.randomUUID();
        ids.add(id);
        return ComponentRegistry.instance()
                .register(new ComponentType<>(id, cls, policy, factory));
    }

    /**
     * @return independently owned binary buffer, released by each test
     */
    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /**
     * Stateful typed component with separate disk and public network fields.
     */
    private static class Counter extends Component
            implements TickableComponent {
        /**
         * Persisted counter synchronized with clients.
         */
        @EcsPersist("value")
        @EcsSync
        int value;

        /**
         * Server-only field that must never appear in a binary frame.
         */
        @EcsPersist("secret")
        int secret;

        /**
         * @param tick manager sequence
         */
        @Override
        public void onTick(long tick) {
            value++;
        }
    }

    /**
     * Stateless component exercises wire mode zero.
     */
    private static final class Plain extends Component {}

    /**
     * Tracked fields exercise wire mode two.
     */
    private static final class Tracked extends NetworkComponent {
        /**
         * Persisted network value.
         */
        final TrackedField<Integer> value = tracked("value", FieldCodecs.intValue(), 0);
    }

    /**
     * Fixture observes the runtime-before-restored-callback ordering.
     */
    private static final class Probe extends Counter {
        /**
         * Runtime-only host association marker.
         */
        boolean runtime;

        /**
         * Value seen by the latest attach callback.
         */
        boolean observedRuntime;

        /**
         * Whether the latest callback ran after publication.
         */
        boolean observedManager;

        /**
         * Records restored runtime binding and manager publication.
         */
        @Override
        public void onAttached() {
            observedRuntime = runtime;
            observedManager = requireOwner().manager().isPresent();
        }
    }

    /**
     * Fixture reports an intentional detach callback failure.
     */
    private static final class BadDetach extends Component {
        /**
         * @throws IllegalStateException always, to exercise cleanup
         */
        @Override
        public void onDetached() {
            throw new IllegalStateException("fixture detach");
        }
    }
}
