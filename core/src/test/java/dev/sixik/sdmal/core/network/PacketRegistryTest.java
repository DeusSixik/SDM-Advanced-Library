package dev.sixik.sdmal.core.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import dev.sixik.sdmal.api.ecs.annotations.EcsData;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.annotations.EcsUseNetworkAdapter;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkPacket;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Native packet formats, discovery, hostile-input rejection and game-thread dispatch.
 */
class PacketRegistryTest {
    /**
     * Initializes Minecraft's native format dependencies.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Records use constructor slots even when stable wire keys sort in another order.
     */
    @Test
    void record_automaticFieldsAndAliases_roundTripsCanonicalArguments() {
        var registry = registry(Message.class);
        var source = new Message(37, "hello", new BlockPos(2, 3, 4), Optional.of(new Data(91)));
        assertEquals(source, registry.decode(registry.encode(source, Side.SERVER), Side.SERVER));
    }

    /**
     * Mutable classes support private noarg construction and inherited concrete generic types.
     */
    @Test
    void class_privateConstructorAndGenericBase_roundTripsOnlyInstanceData() {
        var registry = registry(Mutable.class);
        var source = new Mutable();
        source.number = 41;
        source.value = List.of("a", "b");
        source.ignored = new Object();
        var target = (Mutable) registry.decode(registry.encode(source, Side.CLIENT), Side.CLIENT);
        assertEquals(41, target.number);
        assertEquals(source.value, target.value);
        assertNull(target.ignored);
    }

    /**
     * Reference nulls preserve their nullable binary flags.
     */
    @Test
    void record_nullReferences_roundTrips() {
        var registry = registry(Message.class);
        var source = new Message(-1, null, null, null);
        assertEquals(source, registry.decode(registry.encode(source, Side.SERVER), Side.SERVER));
    }

    /**
     * Empty records require no artificial noarg service provider or payload field.
     */
    @Test
    void record_noFields_roundTrips() {
        var registry = registry(Empty.class);
        assertEquals(
                new Empty(),
                registry.decode(registry.encode(new Empty(), Side.SERVER), Side.SERVER));
    }

    /**
     * Per-field ECS binary adapters work without NBT codecs or registrations.
     */
    @Test
    void adapter_fieldOverride_roundTrips() {
        var registry = registry(Custom.class);
        var source = new Custom(new Value(42));
        assertEquals(source, registry.decode(registry.encode(source, Side.SERVER), Side.SERVER));
    }

    /**
     * Changing field keys or types under an existing packet id is rejected by the fingerprint.
     */
    @Test
    void decode_incompatibleSchema_rejectsBeforeHandle() {
        var sender = registry(VersionOne.class);
        var receiver = registry(VersionTwo.class);
        assertThrows(
                RuntimeException.class,
                () -> receiver.decode(sender.encode(new VersionOne(5), Side.SERVER), Side.SERVER));
    }

    /**
     * Stable aliases permit source renames and declaration reordering without changing the wire
     * layout.
     */
    @Test
    void schema_renamedFieldsWithStableKeys_remainsCompatible() {
        var sender = registry(RenamedOne.class);
        var receiver = registry(RenamedTwo.class);
        var frame = sender.encode(new RenamedOne("example", 17), Side.SERVER);
        assertEquals(new RenamedTwo(17, "example"), receiver.decode(frame, Side.SERVER));
    }

    /**
     * Record constructor validation runs during decoding and prevents invalid values from reaching
     * handle.
     */
    @Test
    void decode_constructorRejectsPayload_failsBeforeHandling() {
        var codec = NetworkSerialization.packetCodec(Validated.class);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.write(buffer, new Validated(1));
            buffer.setInt(8, -1);
            assertThrows(RuntimeException.class, () -> codec.read(buffer));
        } finally {
            buffer.release();
        }
    }

    /**
     * Handler bugs are logged but do not misclassify a successfully decoded message as malformed
     * input.
     */
    @Test
    void dispatch_handlerThrows_logsWithoutRejectingConnection() {
        var registry = registry(Throwing.class);
        var errors = new ArrayList<String>();
        NetworkRuntime.dispatch(
                registry,
                registry.encode(new Throwing(), Side.SERVER),
                Side.SERVER,
                () -> mock(ServerPlayer.class),
                Runnable::run,
                errors::add);
        assertTrue(errors.isEmpty());
    }

    /**
     * Logical direction is checked at sending and receiving, irrespective of Java type.
     */
    @Test
    void side_wrongDirection_rejectsBothPaths() {
        var registry = registry(Empty.class);
        assertThrows(IllegalStateException.class, () -> registry.encode(new Empty(), Side.CLIENT));
        var frame = registry.encode(new Empty(), Side.SERVER);
        assertThrows(IllegalStateException.class, () -> registry.decode(frame, Side.CLIENT));
    }

    /**
     * Unknown ids never trigger reflective construction or handler lookup.
     */
    @Test
    void decode_unknownPacket_rejects() {
        var frame = registry(Empty.class).encode(new Empty(), Side.SERVER);
        assertThrows(IllegalArgumentException.class, () -> registry().decode(frame, Side.SERVER));
    }

    /**
     * Every truncation of a valid frame must fail closed.
     */
    @Test
    void decode_allTruncations_rejects() {
        var registry = registry(Message.class);
        var frame =
                registry.encode(
                        new Message(4, "text", BlockPos.ZERO, Optional.of(new Data(3))),
                        Side.SERVER);
        for (int length = 0; length < frame.length; length++) {
            byte[] truncated = Arrays.copyOf(frame, length);
            assertThrows(
                    RuntimeException.class,
                    () -> registry.decode(truncated, Side.SERVER),
                    "length=" + length);
        }
    }

    /**
     * Bounds apply before adapters can allocate attacker-controlled payloads.
     */
    @Test
    void frames_oversizeAndTrailingData_rejects() {
        var registry = registry(Empty.class, Bytes.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.decode(new byte[PacketRegistry.MAX_LOGICAL_BYTES + 1], Side.SERVER));
        assertThrows(
                RuntimeException.class,
                () -> registry.encode(new Bytes(new byte[PacketRegistry.MAX_LOGICAL_BYTES]), Side.SERVER));
        var frame = registry.encode(new Empty(), Side.SERVER);
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.decode(Arrays.copyOf(frame, frame.length + 1), Side.SERVER));
        frame[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> registry.decode(frame, Side.SERVER));
    }

    /**
     * Exact framing limit remains representable without overflowing the legacy native payload.
     */
    @Test
    void frames_nearLimit_roundTrips() {
        var registry = registry(Bytes.class);
        var source = new Bytes(new byte[PacketRegistry.MAX_BYTES - 100]);
        var decoded = (Bytes) registry.decode(registry.encode(source, Side.SERVER), Side.SERVER);
        assertArrayEquals(source.value(), decoded.value());
    }

    /**
     * Classes with unsupported immutable state receive an actionable codec diagnostic.
     */
    @Test
    void schema_finalClassFieldAndMissingConstructor_rejects() {
        assertThrows(
                RuntimeException.class, () -> NetworkSerialization.packetCodec(FinalField.class));
        assertThrows(
                RuntimeException.class,
                () -> NetworkSerialization.packetCodec(NoConstructor.class));
        assertThrows(
                RuntimeException.class,
                () -> NetworkSerialization.packetCodec(DuplicateKeys.class));
    }

    /**
     * An id collision between independent mods fails at discovery.
     */
    @Test
    void registry_duplicateAndUnannotatedTypes_rejects() {
        assertThrows(
                IllegalStateException.class, () -> registry(VersionOne.class, VersionTwo.class));
        assertThrows(IllegalStateException.class, () -> registry(Unindexed.class));
        assertThrows(
                IllegalStateException.class, () -> registry().encode(new Empty(), Side.SERVER));
    }

    /**
     * Native byte-array framing copies bytes and enforces limits.
     */
    @Test
    void nativeFrame_roundTripAndBounds_releasesCallerOwnedBuffer() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            byte[] frame = {1, 2, 3};
            NetworkRuntime.writeFrame(buffer, frame);
            assertArrayEquals(frame, NetworkRuntime.readFrame(buffer));
            buffer.clear();
            NetworkRuntime.writeFrame(buffer, frame);
            buffer.writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> NetworkRuntime.readFrame(buffer));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> NetworkRuntime.writeFrame(buffer, new byte[0]));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            NetworkRuntime.writeFrame(
                                    buffer, new byte[PacketRegistry.MAX_BYTES + 1]));
        } finally {
            assertTrue(buffer.release());
        }
    }

    /**
     * Handler and decoded constructors run only when the game executor drains.
     */
    @Test
    void dispatch_validPacket_usesGameExecutorAndAuthenticatedSender() {
        var registry = registry(Handled.class);
        var tasks = new ArrayList<Runnable>();
        var failures = new ArrayList<String>();
        var sender = mock(ServerPlayer.class);
        Handled.lastContext = null;
        NetworkRuntime.dispatch(
                registry,
                registry.encode(new Handled(6), Side.SERVER),
                Side.SERVER,
                () -> sender,
                tasks::add,
                failures::add);
        assertNull(Handled.lastContext);
        assertEquals(1, tasks.size());
        tasks.remove(0).run();
        assertSame(sender, Handled.lastContext.sender());
        assertSame(sender, Handled.lastContext.player());
        assertEquals(Side.SERVER, Handled.lastContext.side());
        assertTrue(failures.isEmpty());
        Handled.lastContext.enqueueWork(() -> failures.add("enqueued"));
        assertTrue(failures.isEmpty());
        tasks.remove(0).run();
        assertEquals(List.of("enqueued"), failures);
    }

    /**
     * A disconnected sender's queued message is discarded without decoding.
     */
    @Test
    void dispatch_staleConnection_dropsMessage() {
        var tasks = new ArrayList<Runnable>();
        var errors = new ArrayList<String>();
        Handled.lastContext = null;
        NetworkRuntime.dispatch(
                registry(Handled.class),
                new byte[0],
                Side.SERVER,
                () -> null,
                tasks::add,
                errors::add);
        tasks.remove(0).run();
        assertNull(Handled.lastContext);
        assertTrue(errors.isEmpty());
    }

    /**
     * Malformed packets invoke rejection once and cannot reach handle.
     */
    @Test
    void dispatch_badInput_rejectsWithoutHandling() {
        var errors = new ArrayList<String>();
        Handled.lastContext = null;
        NetworkRuntime.dispatch(
                registry(Handled.class),
                new byte[0],
                Side.SERVER,
                () -> mock(ServerPlayer.class),
                Runnable::run,
                errors::add);
        assertEquals(1, errors.size());
        assertNull(Handled.lastContext);
    }

    /**
     * Receiving side defines the valid player and sender operations.
     */
    @Test
    void context_clientHasNoServerSender_andRejectsInvalidSidePlayer() {
        var client = mock(Player.class);
        var context = new PacketContext(Side.CLIENT, client, Runnable::run);
        assertThrows(IllegalStateException.class, context::sender);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PacketContext(Side.SERVER, client, Runnable::run));
        assertThrows(IllegalArgumentException.class, () -> new Empty().send(client));
    }

    /**
     * Indices can reference records and nested classes without constructing a ServiceLoader
     * instance.
     */
    @Test
    void discovery_resourceOnlyIndex_readsBinaryNames(@TempDir Path directory) throws Exception {
        Path index = directory.resolve(PacketRegistry.INDEX);
        Files.createDirectories(index.getParent());
        Files.writeString(
                index,
                "# fixture\n\n" + Empty.class.getName() + "\n" + Empty.class.getName() + "\n");
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {directory.toUri().toURL()},
                        getClass().getClassLoader())) {
            var registry = PacketRegistry.discover(loader);
            assertEquals(
                    new Empty(),
                    registry.decode(registry.encode(new Empty(), Side.SERVER), Side.SERVER));
        }
    }

    /**
     * A referenced class missing from the runtime is a startup error.
     */
    @Test
    void discovery_missingClass_failsClearly(@TempDir Path directory) throws Exception {
        Path index = directory.resolve(PacketRegistry.INDEX);
        Files.createDirectories(index.getParent());
        Files.writeString(index, "missing.Packet\n");
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {directory.toUri().toURL()},
                        getClass().getClassLoader())) {
            assertThrows(IllegalStateException.class, () -> PacketRegistry.discover(loader));
        }
    }

    /**
     * @param types packet fixtures
     * @return independent packet registry
     */
    @SafeVarargs
    private static PacketRegistry registry(Class<? extends Packet>... types) {
        return new PacketRegistry(List.of(types));
    }

    /**
     * Record whose aliases sort differently from its canonical constructor parameters.
     */
    @NetworkPacket(id = "test:message", side = Side.SERVER)
    private record Message(
            @EcsSync("z") int count,
            @EcsSync("a") String name,
            BlockPos position,
            Optional<Data> data)
            implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Nested ECS data using an explicit stable key.
     */
    @EcsData
    private record Data(@EcsSync("value") long value) {}

    /**
     * Generic superclass whose type is resolved in the concrete packet.
     */
    private static class Base<T> {
        /**
         * Concrete inherited payload value.
         */
        T value;
    }

    /**
     * Mutable packet with excluded local and static state.
     */
    @NetworkPacket(id = "test:mutable", side = Side.CLIENT)
    private static final class Mutable extends Base<List<String>> implements Packet {
        /**
         * Serialized scalar fixture.
         */
        int number;

        /**
         * Local state without a binary adapter.
         */
        transient Object ignored;

        /**
         * Static state without a binary adapter.
         */
        static final Object STATIC = new Object();

        /**
         * Private construction is permitted by cached method handles.
         */
        private Mutable() {}

        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Empty payload fixture.
     */
    @NetworkPacket(id = "test:empty", side = Side.SERVER)
    private record Empty() implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Bounded array fixture.
     */
    @NetworkPacket(id = "test:bytes", side = Side.SERVER)
    private record Bytes(byte[] value) implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * First incompatible packet schema under the same logical id.
     */
    @NetworkPacket(id = "test:version", side = Side.SERVER)
    private record VersionOne(int value) implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Second incompatible packet schema under the same logical id.
     */
    @NetworkPacket(id = "test:version", side = Side.SERVER)
    private record VersionTwo(long value) implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Field-specific binary adapter fixture, without NBT support.
     */
    @NetworkPacket(id = "test:custom", side = Side.SERVER)
    private record Custom(@EcsUseNetworkAdapter(ValueAdapter.class) Value value) implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Unsupported value made serializable through the fixture adapter.
     */
    private record Value(int number) {}

    /**
     * Stateless explicit binary format for Value.
     */
    public static final class ValueAdapter implements EcsNetworkSerializer<Value> {
        /**
         * @return stable test format identifier
         */
        @Override
        public String schemaName() {
            return "test/value/i32/v1";
        }

        /**
         * @param buffer destination native buffer
         * @param value test payload
         */
        @Override
        public void write(FriendlyByteBuf buffer, Value value) {
            buffer.writeInt(value.number());
        }

        /**
         * @param buffer source native buffer
         * @return decoded test payload
         */
        @Override
        public Value read(FriendlyByteBuf buffer) {
            return new Value(buffer.readInt());
        }
    }

    /**
     * Invalid immutable ordinary class.
     */
    private static final class FinalField {
        /**
         * Final fields require record constructor reconstruction.
         */
        final int value = 1;
    }

    /**
     * Invalid ordinary class without a no-argument constructor.
     */
    private static final class NoConstructor {
        /**
         * @param value ignored constructor argument
         */
        NoConstructor(int value) {}
    }

    /**
     * Invalid duplicate binary member keys.
     */
    private record DuplicateKeys(@EcsSync("same") int a, @EcsSync("same") int b) {}

    /**
     * Invalid packet without discovery metadata.
     */
    private record Unindexed() implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Handler fixture recording the connection context observed on the game executor.
     */
    @NetworkPacket(id = "test:handled", side = Side.SERVER)
    private record Handled(int value) implements Packet {
        /**
         * Most recent handled context, or null before executor drain.
         */
        static PacketContext lastContext;

        /**
         * @param context context to capture for assertions
         */
        @Override
        public void handle(PacketContext context) {
            lastContext = context;
        }
    }

    /**
     * First declaration order; persistence keys are also valid packet aliases.
     */
    @NetworkPacket(id = "test:renamed", side = Side.SERVER)
    private record RenamedOne(@EcsPersist("label") String first, @EcsSync("count") int second)
            implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Same logical schema after source fields are renamed and reordered.
     */
    @NetworkPacket(id = "test:renamed", side = Side.SERVER)
    private record RenamedTwo(@EcsSync("count") int amount, @EcsSync("label") String name)
            implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {}
    }

    /**
     * Record that validates semantic bounds through its canonical constructor.
     */
    private record Validated(int value) {
        /**
         * @param value strictly positive fixture value
         */
        private Validated {
            if (value <= 0) throw new IllegalArgumentException("Expected positive value");
        }
    }

    /**
     * Successful decoding followed by a deliberate user-handler failure.
     */
    @NetworkPacket(id = "test:throwing", side = Side.SERVER)
    private record Throwing() implements Packet {
        /**
         * @param context unused fixture context
         */
        @Override
        public void handle(PacketContext context) {
            throw new IllegalStateException("Fixture failure");
        }
    }
}
