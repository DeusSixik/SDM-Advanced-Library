package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.core.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.core.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.core.ecs.WorldObjectComponentType;
import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import dev.sixik.sdmal.core.ecs.codecs.ExternFieldCodecs;
import dev.sixik.sdmal.core.ecs.sync.FieldCodec;
import dev.sixik.sdmal.core.ecs.sync.FieldCodecs;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises native binary layouts, independent capability registries and atomic framed component updates.
 */
class EcsNetworkSerializersTest {
    /**
     * Initializes Minecraft before native identifier/position encodings.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A primitive component frame contains the integer bytes directly, without names or NBT tags.
     */
    @Test
    void frame_singlePrimitive_hasDirectBinaryLayoutAndLeavesNextMessageUnread() {
        var source = new Scalar();
        source.value = 0x01020304;
        var target = new Scalar();
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            assertEquals(18, buffer.writerIndex());
            assertEquals(14, buffer.readInt());
            assertEquals(1, buffer.readVarInt());
            assertEquals(source.networkSchemaFingerprint(), buffer.readLong());
            assertEquals(1, buffer.readVarInt());
            assertEquals(0x01020304, buffer.readInt());
            buffer.readerIndex(0);
            buffer.writeInt(0x55667788);
            target.readNetwork(buffer);
            assertEquals(source.value, target.value);
            assertEquals(0x55667788, buffer.readInt());
            assertEquals(0, buffer.readableBytes());
            assertEquals(1, buffer.refCnt());
        } finally {
            buffer.release();
        }
    }

    /**
     * Persistence-only state stays local; sync-only values travel without becoming durable.
     */
    @Test
    void annotations_persistAndSync_selectIndependentFields() {
        var source = new Selection();
        source.energy = 42;
        source.secret = 99;
        source.visual = "network";
        CompoundTag archive = new CompoundTag();
        source.writeNbt(archive);
        assertNotNull(archive.get("energy"));
        assertNotNull(archive.get("secret"));
        assertNull(archive.get("visual"));
        var target = new Selection();
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            target.readNetwork(buffer);
            assertEquals(42, target.energy);
            assertEquals(7, target.secret);
            assertEquals("network", target.visual);
            source.visual = null;
            buffer.clear();
            source.writeNetwork(buffer);
            target.readNetwork(buffer);
            assertNull(target.visual);
        } finally {
            buffer.release();
        }
    }

    /**
     * Generic records reuse stable persisted keys and encode their members directly through native adapters.
     */
    @Test
    void structures_recordsNestedGenericsNullableCollections_roundTrip() {
        var source = new Structured();
        UUID id = UUID.randomUUID();
        source.batteries = new ArrayList<>(Arrays.asList(new Battery(1, 5), null));
        source.values.put(id, Optional.of(new Box<>(List.of(id))));
        source.values.put(null, Optional.empty());
        source.networkOnly = new WireData(5);
        var target = new Structured();
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            target.readNetwork(buffer);
            assertEquals(source.batteries, target.batteries);
            assertEquals(source.values, target.values);
            assertEquals(new WireData(5), target.networkOnly);
            target.batteries.add(new Battery(0, 1));
            assertEquals(2, source.batteries.size());
            assertNotSame(EcsNetworkSerializers.serializer(new TypeRef<List<UUID>>() {
                    }),
                    EcsNetworkSerializers.serializer(new TypeRef<List<String>>() {
                    }));
        } finally {
            buffer.release();
        }
    }

    /**
     * Private inherited generic fields preserve concrete type arguments and volatile access modes.
     */
    @Test
    void inheritance_genericPrivateFields_roundTrip() {
        var source = new Child();
        source.set(new Battery(3, 8));
        source.count = 4;
        var target = new Child();
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            target.readNetwork(buffer);
            assertEquals(new Battery(3, 8), target.get());
            assertEquals(4, target.count);
        } finally {
            buffer.release();
        }
    }

    /**
     * Matched keys/types are compatible despite different Java names and declaration order.
     */
    @Test
    void schema_declarationOrderAndJavaNames_doNotChangeCompatibility() {
        var source = new CompatibleA();
        source.first = 41;
        source.last = "last";
        var target = new CompatibleB();
        assertEquals(source.networkSchemaFingerprint(), target.networkSchemaFingerprint());
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            target.readNetwork(buffer);
            assertEquals(41, target.renamedFirst);
            assertEquals("last", target.renamedLast);
        } finally {
            buffer.release();
        }
    }

    /**
     * Nested value layout changes are fingerprinted before any fields can be decoded or written.
     */
    @Test
    void schema_differentMemberType_rejectsWithoutConsumingFrame() {
        var source = new Scalar();
        source.value = 99;
        var target = new LongScalar();
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            assertNotEquals(source.networkSchemaFingerprint(), target.networkSchemaFingerprint());
            assertThrows(EcsSerializationException.class, () -> target.readNetwork(buffer));
            assertEquals(0, buffer.readerIndex());
            assertEquals(7, target.value);
        } finally {
            buffer.release();
        }
    }

    /**
     * Every truncation boundary fails atomically and restores the original reader position.
     */
    @Test
    void frame_truncatedAtEveryByte_neverPartiallyUpdatesComponent() {
        var source = new Selection();
        source.energy = 44;
        source.visual = "hello";
        FriendlyByteBuf original = buffer();
        try {
            source.writeNetwork(original);
            for (int length = 0; length < original.writerIndex(); length++) {
                FriendlyByteBuf truncated = new FriendlyByteBuf(original.copy(0, length));
                try {
                    var target = new Selection();
                    assertThrows(EcsSerializationException.class, () -> target.readNetwork(truncated));
                    assertEquals(100, target.energy);
                    assertEquals("initial", target.visual);
                    assertEquals(0, truncated.readerIndex());
                } finally {
                    truncated.release();
                }
            }
        } finally {
            original.release();
        }
    }

    /**
     * Corrupt metadata, presence flags and trailing bytes cannot silently alter component state.
     *
     * @param corruption distinct malformed packet category
     */
    @ParameterizedTest
    @ValueSource(strings = {"length", "oversize", "version", "fingerprint", "count", "flag", "trailing"})
    void frame_malformedMetadataOrValues_rejectsAtomically(String corruption) {
        var source = new Selection();
        source.energy = 44;
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            switch (corruption) {
                case "length" -> buffer.setInt(0, -1);
                case "oversize" -> buffer.setInt(0, EcsNetworkSchema.MAX_BYTES + 1);
                case "version" -> buffer.setByte(4, 2);
                case "fingerprint" -> buffer.setLong(5, 0);
                case "count" -> buffer.setByte(13, 3);
                case "flag" -> buffer.setByte(18, 2);
                case "trailing" -> {
                    buffer.writeByte(0);
                    buffer.setInt(0, buffer.writerIndex() - 4);
                }
                default -> fail("Unknown fixture corruption");
            }
            var target = new Selection();
            assertThrows(EcsSerializationException.class, () -> target.readNetwork(buffer));
            assertEquals(100, target.energy);
            assertEquals("initial", target.visual);
            assertEquals(0, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }

    /**
     * Binary transport never invokes the dual adapter's NBT methods, even when the same field is persisted.
     */
    @Test
    void custom_dualCapabilityAdapter_callsOnlyFriendlyByteBufMethods() {
        var adapter = new DualAdapter();
        EcsSerializers.register(External.class, adapter);
        EcsNetworkSerializers.register(External.class, adapter);
        var source = new DualComponent();
        source.value = new External(71);
        var target = new DualComponent();
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            target.readNetwork(buffer);
            assertEquals(new External(71), target.value);
            var error = assertThrows(EcsSerializationException.class, () -> source.writeNbt(new CompoundTag()));
            assertInstanceOf(AssertionError.class, error.getCause());
        } finally {
            buffer.release();
        }
    }

    /**
     * Registered network-only types do not need NBT adapters, and field overrides retain separate formats.
     */
    @Test
    void custom_networkOnlyTypeAndFieldOverride_needNoPersistenceAdapter() {
        EcsNetworkSerializers.register(OnlyWire.class, new WireAdapter());
        var source = new WireComponent();
        source.value = new OnlyWire(13);
        source.compact = new OnlyWire(17);
        EcsSerializers.validateComponent(WireComponent.class);
        EcsNetworkSerializers.validateComponent(WireComponent.class);
        var target = new WireComponent();
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            target.readNetwork(buffer);
            assertEquals(source.value, target.value);
            assertEquals(source.compact, target.compact);
            assertThrows(EcsSerializationException.class, () -> EcsSerializers.serializer(OnlyWire.class));
            assertThrows(IllegalStateException.class, () -> EcsNetworkSerializers.register(OnlyWire.class, new WireAdapter()));
        } finally {
            buffer.release();
        }
    }

    /**
     * A failed unresolved binary schema may be corrected before its first successful publication.
     */
    @Test
    void schema_missingNativeAdapter_canRegisterAndRetry() {
        assertThrows(EcsSerializationException.class, () -> EcsNetworkSerializers.validateComponent(LateComponent.class));
        EcsNetworkSerializers.register(Late.class, EcsNetworkBuiltins.of("test:late/i32/v1",
                (buffer, value) -> buffer.writeInt(value.value()), buffer -> new Late(buffer.readInt())));
        assertDoesNotThrow(() -> EcsNetworkSerializers.validateComponent(LateComponent.class));
    }

    /**
     * A generic native adapter registration retains its exact nested argument.
     */
    @Test
    void custom_genericAdapterRegistration_doesNotEraseArguments() {
        var token = new TypeRef<Envelope<UUID>>() {
        };
        EcsNetworkSerializers.register(token, EcsNetworkBuiltins.of("test:uuid-envelope/v1",
                (buffer, value) -> buffer.writeUUID(value.value()), buffer -> new Envelope<>(buffer.readUUID())));
        var serializer = EcsNetworkSerializers.serializer(new TypeRef<Envelope<UUID>>() {
        });
        var value = new Envelope<>(UUID.randomUUID());
        FriendlyByteBuf buffer = buffer();
        try {
            serializer.write(buffer, value);
            assertEquals(value, serializer.read(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(EcsSerializationException.class, () -> EcsNetworkSerializers.serializer(new TypeRef<Envelope<String>>() {
        }));
    }

    /**
     * Stable format names are mandatory; adapters returning null violate the present-value contract.
     */
    @Test
    void custom_invalidAdapterContracts_reject() {
        assertThrows(EcsSerializationException.class, () -> EcsNetworkSerializers.register(Invalid.class,
                EcsNetworkBuiltins.of(" ", (buffer, value) -> buffer.writeByte(0), buffer -> new Invalid())));
        EcsNetworkSerializers.register(Invalid.class,
                EcsNetworkBuiltins.of("test:null/v1", (buffer, value) -> buffer.writeByte(0), buffer -> null));
        var serializer = EcsNetworkSerializers.serializer(Invalid.class);
        FriendlyByteBuf buffer = buffer();
        try {
            assertThrows(EcsSerializationException.class, () -> serializer.read(buffer));
        } finally {
            buffer.release();
        }
    }

    /**
     * Writing more than the frame cap rejects the component and restores the caller's writer position.
     */
    @Test
    void frame_excessiveAdapterOutput_rejectsWithWriterRollback() {
        var component = new HugeComponent();
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeByte(7);
            assertThrows(EcsSerializationException.class, () -> component.writeNetwork(buffer));
            assertEquals(1, buffer.writerIndex());
            assertEquals(7, buffer.getByte(0));
        } finally {
            buffer.release();
        }
    }

    /**
     * Invalid annotations and unsafe automatic types are rejected before a world is created.
     */
    @ParameterizedTest
    @MethodSource("invalidComponents")
    void schema_invalidWireMember_rejectsAtRegistration(Class<? extends AnnotatedWorldObjectComponent> type) {
        var error = assertThrows(EcsSerializationException.class, () -> EcsNetworkSerializers.validateComponent(type));
        assertTrue(error.getMessage().contains(type.getName()));
    }

    /**
     * @return distinct invalid binary schema declarations
     */
    private static List<Class<? extends AnnotatedWorldObjectComponent>> invalidComponents() {
        return List.of(NoKey.class, StaticField.class, FinalField.class, DuplicateKey.class, Raw.class,
                Wildcard.class, MissingSync.class, WrongOverride.class, UnknownType.class, RecursiveComponent.class);
    }

    /**
     * Component type registration validates wire schemas eagerly, independently of NBT validity.
     */
    @Test
    void registry_invalidWireSchema_isNotInstalled() {
        String id = "test:wire_invalid_" + UUID.randomUUID();
        var registry = WorldObjectComponentRegistry.instance();
        var type = new WorldObjectComponentType<>(id, NoKey.class, WorldObjectComponentType.DuplicatePolicy.REJECT, NoKey::new);
        assertThrows(EcsSerializationException.class, () -> registry.register(type));
        assertTrue(registry.get(id).isEmpty());
    }

    /**
     * Invalid data in a late record constructor leaves previously decoded primitive fields untouched.
     */
    @Test
    void record_invalidCanonicalState_rollsBackComponentAndReaderIndex() {
        var source = new BatteryComponent();
        source.first = 99;
        FriendlyByteBuf buffer = buffer();
        try {
            source.writeNetwork(buffer);
            buffer.setInt(19, 20);
            var target = new BatteryComponent();
            assertThrows(EcsSerializationException.class, () -> target.readNetwork(buffer));
            assertEquals(1, target.first);
            assertEquals(new Battery(2, 10), target.battery);
            assertEquals(0, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }

    /**
     * Custom encoder failures restore writerIndex and preserve the pre-existing packet prefix.
     */
    @Test
    void frame_failedAdapterWrite_restoresBufferPosition() {
        var source = new FailingComponent();
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeInt(0x11223344);
            assertThrows(EcsSerializationException.class, () -> source.writeNetwork(buffer));
            assertEquals(4, buffer.writerIndex());
            assertEquals(0x11223344, buffer.getInt(0));
            assertEquals(1, buffer.refCnt());
        } finally {
            buffer.release();
        }
    }

    /**
     * Container readers reject oversized/truncated lengths before allocating elements and duplicate map keys.
     */
    @Test
    void collections_badCountsAndDuplicates_reject() {
        var list = EcsNetworkSerializers.serializer(new TypeRef<List<Integer>>() {
        });
        var map = EcsNetworkSerializers.serializer(new TypeRef<Map<String, Integer>>() {
        });
        FriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(65_537);
            assertThrows(EcsSerializationException.class, () -> list.read(buffer));
            buffer.clear();
            buffer.writeVarInt(65_536);
            assertThrows(EcsSerializationException.class, () -> list.read(buffer));
            buffer.clear();
            buffer.writeVarInt(2);
            for (int index = 0; index < 2; index++) {
                buffer.writeByte(1);
                buffer.writeUtf("same");
                buffer.writeByte(1);
                buffer.writeInt(index);
            }
            assertThrows(EcsSerializationException.class, () -> map.read(buffer));
        } finally {
            buffer.release();
        }
    }

    /**
     * Scalar values use the version's actual byte buffer, including numeric boundaries and exact floats.
     */
    @Test
    void builtins_nativeScalarsMinecraftEnumsAndArrays_roundTrip() {
        roundTrip(Boolean.class, true);
        roundTrip(Byte.class, Byte.MIN_VALUE);
        roundTrip(Short.class, Short.MIN_VALUE);
        roundTrip(Character.class, Character.MAX_VALUE);
        roundTrip(Integer.class, Integer.MIN_VALUE);
        roundTrip(Long.class, Long.MAX_VALUE);
        roundTrip(Float.class, 1.25f);
        roundTrip(Double.class, -2.5);
        roundTrip(String.class, "текст 世界");
        roundTrip(UUID.class, UUID.randomUUID());
        roundTrip(BlockPos.class, new BlockPos(-12, -50, 19));
        roundTrip(ChunkPos.class, new ChunkPos(-42, 6));
        roundTrip(State.class, State.RUNNING);
        var section = EcsNetworkSerializers.serializer(SectionPos.class);
        FriendlyByteBuf buffer = buffer();
        try {
            var value = SectionPos.of(-5, 3, 6);
            section.write(buffer, value);
            assertEquals(value.asLong(), section.read(buffer).asLong());
            buffer.clear();
            var identifier = dev.sixik.sdmal.SDMAdvancedLibrary.id("example");
            var codec = EcsNetworkSerializers.fromCodec(ExternFieldCodecs.resourceLocation());
            codec.write(buffer, identifier);
            assertEquals(identifier, codec.read(buffer));
            buffer.clear();
            var ints = EcsNetworkSerializers.serializer(int[].class);
            ints.write(buffer, new int[]{1, -1});
            assertArrayEquals(new int[]{1, -1}, ints.read(buffer));
            buffer.clear();
            var bytes = EcsNetworkSerializers.serializer(byte[].class);
            bytes.write(buffer, new byte[]{-1, 0});
            assertArrayEquals(new byte[]{-1, 0}, bytes.read(buffer));
            buffer.clear();
            var longs = EcsNetworkSerializers.serializer(long[].class);
            longs.write(buffer, new long[]{Long.MIN_VALUE});
            assertArrayEquals(new long[]{Long.MIN_VALUE}, longs.read(buffer));
        } finally {
            buffer.release();
        }
    }

    /**
     * Existing FieldCodec network methods remain usable without invoking its NBT methods.
     */
    @Test
    void adapters_fromExistingCodec_usesNativeMethodsOnly() {
        FieldCodec<Integer> existing = FieldCodec.<Integer>builder().schema("test:native/i32/v1")
                .nbt((tag, key, value) -> {
                            throw new AssertionError("NBT write called");
                        },
                        (tag, key, fallback) -> {
                            throw new AssertionError("NBT read called");
                        })
                .network((buffer, value) -> buffer.writeInt(value), FriendlyByteBuf::readInt).build();
        var adapter = EcsNetworkSerializers.fromCodec(existing);
        FriendlyByteBuf buffer = buffer();
        try {
            adapter.write(buffer, 123);
            assertEquals(4, buffer.readableBytes());
            assertEquals(123, adapter.read(buffer));
        } finally {
            buffer.release();
        }
    }

    /**
     * Binary component IO must be dispatched onto the owning world thread.
     *
     * @throws Exception if the worker fails to complete
     */
    @Test
    void component_wrongWorldThread_rejectsBeforeTouchingBuffer() throws Exception {
        try (var manager = new WorldObjectManager()) {
            var component = manager.create().attach(new Scalar());
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    FriendlyByteBuf buffer = buffer();
                    try {
                        assertThrows(IllegalStateException.class, () -> component.writeNetwork(buffer));
                        assertThrows(IllegalStateException.class, () -> component.readNetwork(buffer));
                        assertEquals(0, buffer.writerIndex());
                    } finally {
                        buffer.release();
                    }
                }).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Checks native round-trip equality; the float fixtures are exactly representable binary values.
     *
     * @param type  concrete class
     * @param value expected value
     * @param <T>   value type
     */
    private static <T> void roundTrip(Class<T> type, T value) {
        var serializer = EcsNetworkSerializers.serializer(type);
        FriendlyByteBuf buffer = buffer();
        try {
            serializer.write(buffer, value);
            assertEquals(value, serializer.read(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    /**
     * @return caller-owned native buffer with a reusable initial capacity
     */
    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer(256));
    }

    /**
     * One primitive field for exact packet layout checks.
     */
    private static final class Scalar extends AnnotatedWorldObjectComponent {
        /**
         * Plain wire integer.
         */
        @EcsSync("value")
        private int value;
    }

    /**
     * Different binary type under the same wire key.
     */
    private static final class LongScalar extends AnnotatedWorldObjectComponent {
        /**
         * Default retained after schema mismatch.
         */
        @EcsSync("value")
        private long value = 7;
    }

    /**
     * Independent selection of persisted and network-only state.
     */
    private static final class Selection extends AnnotatedWorldObjectComponent {
        /**
         * Stable key shared by both transports.
         */
        @EcsPersist("energy")
        @EcsSync
        private int energy = 100;
        /**
         * Persisted state that never travels.
         */
        @EcsPersist("secret")
        private int secret = 7;
        /**
         * Network-only visual state.
         */
        @EcsSync("visual")
        private String visual = "initial";
    }

    /**
     * Validated custom data whose stable NBT keys are reused for native record layout.
     *
     * @param stored   units in [0, capacity]
     * @param capacity maximum units
     */
    @EcsData
    private record Battery(@EcsPersist("stored") int stored, @EcsPersist("capacity") int capacity) {
        /**
         * Validates reconstructed state.
         *
         * @param stored   stored units
         * @param capacity maximum units
         */
        private Battery {
            if (stored < 0 || stored > capacity) throw new IllegalArgumentException("Invalid battery");
        }
    }

    /**
     * Generic nested record fixture.
     *
     * @param value exact nested value
     * @param <T>   concrete nested type
     */
    @EcsData
    private record Box<T>(@EcsPersist("value") T value) {
    }

    /**
     * Network-only record does not require persistence annotations.
     *
     * @param value wire integer
     */
    @EcsData
    private record WireData(@EcsSync("value") int value) {
    }

    /**
     * Nested binary structures fixture.
     */
    private static final class Structured extends AnnotatedWorldObjectComponent {
        /**
         * Nullable record elements.
         */
        @EcsSync("batteries")
        private List<Battery> batteries = new ArrayList<>();
        /**
         * Nested specialized record/map/optional types.
         */
        @EcsSync("values")
        private Map<UUID, Optional<Box<List<UUID>>>> values = new LinkedHashMap<>();
        /**
         * Data selected only for network.
         */
        @EcsSync("wire")
        private WireData networkOnly;
    }

    /**
     * Generic private inherited state.
     *
     * @param <T> concrete child value
     */
    private abstract static class Parent<T> extends AnnotatedWorldObjectComponent {
        /**
         * Inherited generic field.
         */
        @EcsSync("base")
        private T value;

        /**
         * Assigns fixture data.
         *
         * @param value new generic value
         */
        final void set(T value) {
            this.value = value;
        }

        /**
         * @return current generic data
         */
        final T get() {
            return value;
        }
    }

    /**
     * Concrete generic specialization.
     */
    private static final class Child extends Parent<Battery> {
        /**
         * Native volatile integer field.
         */
        @EcsSync("count")
        private volatile int count;
    }

    /**
     * Sender declaration order differs from receiver order.
     */
    private static final class CompatibleA extends AnnotatedWorldObjectComponent {
        /**
         * First key.
         */
        @EcsSync("a")
        private int first;
        /**
         * Last key.
         */
        @EcsSync("z")
        private String last;
    }

    /**
     * Receiver uses different Java names and declaration order.
     */
    private static final class CompatibleB extends AnnotatedWorldObjectComponent {
        /**
         * Last key declared first.
         */
        @EcsSync("z")
        private String renamedLast;
        /**
         * First key declared last.
         */
        @EcsSync("a")
        private int renamedFirst;
    }

    /**
     * Unannotated third-party data supplied by a dual adapter.
     *
     * @param value payload
     */
    private record External(int value) {
    }

    /**
     * Proves the network capability never invokes persistence methods.
     */
    private static final class DualAdapter implements EcsSerializer<External>, EcsNetworkSerializer<External> {
        /**
         * Deliberately fails if the NBT route is invoked.
         *
         * @param value fixture value
         * @return never returns
         */
        @Override
        public Tag write(External value) {
            throw new AssertionError("NBT write called");
        }

        /**
         * Deliberately fails if the NBT route is invoked.
         *
         * @param tag fixture tag
         * @return never returns
         */
        @Override
        public External read(Tag tag) {
            throw new AssertionError("NBT read called");
        }

        /**
         * Writes a raw integer.
         *
         * @param buffer destination
         * @param value  external value
         */
        @Override
        public void write(FriendlyByteBuf buffer, External value) {
            buffer.writeInt(value.value());
        }

        /**
         * Reads a raw integer.
         *
         * @param buffer source
         * @return reconstructed value
         */
        @Override
        public External read(FriendlyByteBuf buffer) {
            return new External(buffer.readInt());
        }

        /**
         * @return fixed fixture layout
         */
        @Override
        public String schemaName() {
            return "test:dual/i32/v1";
        }
    }

    /**
     * Component using a dual adapter for one selected field.
     */
    private static final class DualComponent extends AnnotatedWorldObjectComponent {
        /**
         * Both transports select this field; only network is exercised.
         */
        @EcsPersist("value")
        @EcsSync
        private External value;
    }

    /**
     * Third-party data with no NBT format.
     *
     * @param value integer payload
     */
    private record OnlyWire(int value) {
    }

    /**
     * Network-only adapter requiring no persistence implementation.
     */
    private static final class WireAdapter implements EcsNetworkSerializer<OnlyWire> {
        /**
         * Writes a raw integer.
         *
         * @param buffer destination
         * @param value  payload
         */
        @Override
        public void write(FriendlyByteBuf buffer, OnlyWire value) {
            buffer.writeInt(value.value());
        }

        /**
         * Reads a raw integer.
         *
         * @param buffer source
         * @return value
         */
        @Override
        public OnlyWire read(FriendlyByteBuf buffer) {
            return new OnlyWire(buffer.readInt());
        }

        /**
         * @return stable fixture format
         */
        @Override
        public String schemaName() {
            return "test:only-wire/i32/v1";
        }
    }

    /**
     * Per-field variant with a compact integer layout.
     */
    public static final class CompactAdapter implements EcsNetworkSerializer<OnlyWire> {
        /**
         * Creates a stateless annotation adapter.
         */
        public CompactAdapter() {
        }

        /**
         * Writes a variable integer.
         *
         * @param buffer destination
         * @param value  payload
         */
        @Override
        public void write(FriendlyByteBuf buffer, OnlyWire value) {
            buffer.writeVarInt(value.value());
        }

        /**
         * Reads a variable integer.
         *
         * @param buffer source
         * @return value
         */
        @Override
        public OnlyWire read(FriendlyByteBuf buffer) {
            return new OnlyWire(buffer.readVarInt());
        }

        /**
         * @return format distinct from the global fixed integer adapter
         */
        @Override
        public String schemaName() {
            return "test:only-wire/varint/v1";
        }
    }

    /**
     * Both global and local binary representations of one network-only type.
     */
    private static final class WireComponent extends AnnotatedWorldObjectComponent {
        /**
         * Global fixed-width adapter.
         */
        @EcsSync("value")
        private OnlyWire value;
        /**
         * Field-local compact adapter.
         */
        @EcsSync("compact")
        @EcsUseNetworkAdapter(CompactAdapter.class)
        private OnlyWire compact;
    }

    /**
     * Initially unsupported value whose binary registration is added after a failed validation.
     *
     * @param value integer payload
     */
    private record Late(int value) {
    }

    /**
     * Retryable native schema fixture.
     */
    private static final class LateComponent extends AnnotatedWorldObjectComponent {
        /**
         * Value requiring registration.
         */
        @EcsSync("late")
        private Late value;
    }

    /**
     * First primitive must remain unchanged if the late record is rejected.
     */
    private static final class BatteryComponent extends AnnotatedWorldObjectComponent {
        /**
         * First-decoded primitive.
         */
        @EcsSync("a")
        private int first = 1;
        /**
         * Last-decoded validated record.
         */
        @EcsSync("b")
        private Battery battery = new Battery(2, 10);
    }

    /**
     * Adapter that writes a partial value before throwing.
     */
    public static final class FailingAdapter implements EcsNetworkSerializer<Integer> {
        /**
         * Creates the failing fixture adapter.
         */
        public FailingAdapter() {
        }

        /**
         * Writes a partial value and then rejects it.
         *
         * @param buffer destination
         * @param value  fixture integer
         */
        @Override
        public void write(FriendlyByteBuf buffer, Integer value) {
            buffer.writeInt(value);
            throw new IllegalArgumentException("Fixture");
        }

        /**
         * Reads a fixture integer.
         *
         * @param buffer source
         * @return decoded integer
         */
        @Override
        public Integer read(FriendlyByteBuf buffer) {
            return buffer.readInt();
        }

        /**
         * @return stable fixture name
         */
        @Override
        public String schemaName() {
            return "test:failing/v1";
        }
    }

    /**
     * Component with a throwing binary writer.
     */
    private static final class FailingComponent extends AnnotatedWorldObjectComponent {
        /**
         * Value encoded by a failure-injection adapter.
         */
        @EcsSync("value")
        @EcsUseNetworkAdapter(FailingAdapter.class)
        private int value = 5;
    }

    /**
     * Enum-name encoding fixture.
     */
    private enum State {
        /**
         * Running value.
         */
        RUNNING
    }

    /**
     * Generic external type without EcsData opt-in.
     *
     * @param value typed payload
     * @param <T>   concrete payload type
     */
    private record Envelope<T>(T value) {
    }

    /**
     * Invalid adapter value fixture.
     */
    private record Invalid() {
    }

    /**
     * Adapter deliberately exceeding the complete component frame budget.
     */
    public static final class HugeAdapter implements EcsNetworkSerializer<Integer> {
        /**
         * Creates the failure-injection adapter.
         */
        public HugeAdapter() {
        }

        /**
         * Fills a payload beyond its allowed complete frame size.
         *
         * @param buffer output buffer
         * @param value  ignored fixture value
         */
        @Override
        public void write(FriendlyByteBuf buffer, Integer value) {
            buffer.writeZero(EcsNetworkSchema.MAX_BYTES);
        }

        /**
         * Returns a fixture value; only the writer is exercised.
         *
         * @param buffer input buffer
         * @return dummy integer
         */
        @Override
        public Integer read(FriendlyByteBuf buffer) {
            return 0;
        }

        /**
         * @return stable fixture format name
         */
        @Override
        public String schemaName() {
            return "test:huge/v1";
        }
    }

    /**
     * Complete-frame size rejection fixture.
     */
    private static final class HugeComponent extends AnnotatedWorldObjectComponent {
        /**
         * Oversized encoded output.
         */
        @EcsSync("value")
        @EcsUseNetworkAdapter(HugeAdapter.class)
        private int value;
    }

    /**
     * No stable name exists when there is no persistence key to reuse.
     */
    private static final class NoKey extends AnnotatedWorldObjectComponent {
        /**
         * Invalid implicit key.
         */
        @EcsSync
        private int value;
    }

    /**
     * Invalid static wire state.
     */
    private static final class StaticField extends AnnotatedWorldObjectComponent {
        /**
         * Static fields cannot be restored per component.
         */
        @EcsSync("value")
        private static int value;
    }

    /**
     * Invalid immutable component state.
     */
    private static final class FinalField extends AnnotatedWorldObjectComponent {
        /**
         * Final fields cannot be committed by a component reader.
         */
        @EcsSync("value")
        private final int value = 1;
    }

    /**
     * Duplicate binary keys are invalid.
     */
    private static final class DuplicateKey extends AnnotatedWorldObjectComponent {
        /**
         * First conflicting key.
         */
        @EcsSync("same")
        private int first;
        /**
         * Second conflicting key.
         */
        @EcsSync("same")
        private int second;
    }

    /**
     * A raw list has no finite element schema.
     */
    private static final class Raw extends AnnotatedWorldObjectComponent {
        /**
         * Invalid erased type.
         */
        @SuppressWarnings("rawtypes")
        @EcsSync("value")
        private List value;
    }

    /**
     * A wildcard list has no concrete element format.
     */
    private static final class Wildcard extends AnnotatedWorldObjectComponent {
        /**
         * Invalid wildcard type.
         */
        @EcsSync("value")
        private List<?> value;
    }

    /**
     * Field-local formats require selection of the field itself.
     */
    private static final class MissingSync extends AnnotatedWorldObjectComponent {
        /**
         * Invalid orphan override.
         */
        @EcsUseNetworkAdapter(CompactAdapter.class)
        private OnlyWire value;
    }

    /**
     * Field-local format value type must match.
     */
    private static final class WrongOverride extends AnnotatedWorldObjectComponent {
        /**
         * OnlyWire adapter cannot encode int.
         */
        @EcsSync("value")
        @EcsUseNetworkAdapter(CompactAdapter.class)
        private int value;
    }

    /**
     * Arbitrary objects require an explicit network adapter.
     */
    private static final class UnknownType extends AnnotatedWorldObjectComponent {
        /**
         * Unsupported automatic type.
         */
        @EcsSync("value")
        private Object value;
    }

    /**
     * Automatic recursive record graphs require a custom reference protocol.
     *
     * @param next recursive value
     */
    @EcsData
    private record Recursive(@EcsPersist("next") Recursive next) {
    }

    /**
     * Recursive binary schema fixture.
     */
    private static final class RecursiveComponent extends AnnotatedWorldObjectComponent {
        /**
         * Invalid automatic recursive graph.
         */
        @EcsSync("value")
        private Recursive value;
    }
}
