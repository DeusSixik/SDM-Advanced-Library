package dev.sixik.sdmal.core.ecs.serialization;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.WorldObjectComponentType;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;
import dev.sixik.sdmal.api.ecs.annotations.EcsData;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsUseAdapter;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;
import dev.sixik.sdmal.api.ecs.serialization.TypeRef;
import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Exercises actual NBT, schema errors, generic adapters and complete world archive integration.
 */
class EcsSerializersTest {
    /**
     * Initializes vanilla NBT/codec dependencies before resolving Minecraft adapters.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Private annotated fields survive without getters, Field handles or per-field codecs.
     */
    @Test
    void component_privateFields_roundTripWithoutCodecs() {
        var source = new PlainComponent();
        source.energy = 23;
        source.owner = UUID.randomUUID();
        source.cached = 99;
        source.battery = new Battery(7, 10);
        CompoundTag saved = new CompoundTag();
        saved.putString("unknown", "retained");
        source.writeNbt(saved);
        assertNull(saved.get("cached"));
        assertEquals("retained", NbtAccess.string(saved.get("unknown"), ""));
        var copy = new PlainComponent();
        copy.readNbt(saved);
        assertEquals(23, copy.energy);
        assertEquals(source.owner, copy.owner);
        assertEquals(new Battery(7, 10), copy.battery);
        assertEquals(0, copy.cached);
    }

    /**
     * Null envelopes clear initialized references; missing keys retain each instance's initialized
     * defaults.
     */
    @Test
    void component_nullAndMissing_distinguishesClearingFromDefault() {
        var source = new PlainComponent();
        source.owner = null;
        source.battery = null;
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        var copy = new PlainComponent();
        copy.readNbt(saved);
        assertNull(copy.owner);
        assertNull(copy.battery);
        assertEquals(100, copy.energy);
        var untouched = new PlainComponent();
        untouched.readNbt(new CompoundTag());
        assertNotNull(untouched.owner);
        assertEquals(new Battery(0, 100), untouched.battery);
    }

    /**
     * Invalid later data cannot install earlier successfully decoded component values.
     */
    @Test
    void component_invalidPayload_doesNotPartiallyMutateFields() {
        var source = new PlainComponent();
        source.energy = 25;
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        ((CompoundTag) saved.get("owner")).put("v", IntTag.valueOf(4));
        CompoundTag original = saved.copy();
        var target = new PlainComponent();
        var failure = assertThrows(EcsSerializationException.class, () -> target.readNbt(saved));
        assertTrue(failure.getMessage().contains(".owner"));
        assertEquals(100, target.energy);
        assertEquals(original, saved);
        ((CompoundTag) saved.get("energy")).putBoolean("$present", false);
        ((CompoundTag) saved.get("energy")).remove("v");
        assertThrows(EcsSerializationException.class, () -> target.readNbt(saved));
        assertEquals(100, target.energy);
    }

    /**
     * Records use their canonical constructor and cannot bypass validation through reflective field
     * writes.
     */
    @Test
    void record_invalidState_runsCanonicalConstructorAndRejects() {
        var serializer = NbtSerialization.serializer(Battery.class);
        CompoundTag encoded = (CompoundTag) serializer.write(new Battery(2, 5));
        ((CompoundTag) encoded.get("stored")).putInt("v", 6);
        var failure = assertThrows(EcsSerializationException.class, () -> serializer.read(encoded));
        assertTrue(failure.getMessage().contains("canonical constructor"));
        encoded.remove("stored");
        assertThrows(EcsSerializationException.class, () -> serializer.read(encoded));
    }

    /**
     * Nested generic records, nullable list elements and UUID-keyed maps retain their exact
     * declared types.
     */
    @Test
    void generics_nestedCollectionsAndRecords_roundTripWithoutErasure() {
        var source = new GenericComponent();
        UUID id = UUID.randomUUID();
        source.batteries = new ArrayList<>(Arrays.asList(new Battery(1, 5), null));
        source.owners.put(id, Optional.of(new Box<>(List.of(id))));
        source.owners.put(null, Optional.empty());
        source.empty = Optional.empty();
        source.absent = null;
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        var copy = new GenericComponent();
        copy.readNbt(saved);
        assertEquals(source.batteries, copy.batteries);
        assertEquals(source.owners, copy.owners);
        assertEquals(Optional.empty(), copy.empty);
        assertNull(copy.absent);
        copy.batteries.add(new Battery(0, 1));
        assertEquals(2, source.batteries.size());
        assertNotSame(
                NbtSerialization.serializer(new TypeRef<List<UUID>>() {}),
                NbtSerialization.serializer(new TypeRef<List<String>>() {}));
    }

    /**
     * Private inherited fields resolve concrete superclass arguments and stable NBT names across
     * Java renames.
     */
    @Test
    void inheritance_genericBaseAndStableKey_restoreCorrectly() {
        var source = new GenericChild();
        source.setData(new Battery(4, 10));
        source.renamedJavaField = 9;
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        assertNotNull(saved.get("stable"));
        assertNull(saved.get("renamedJavaField"));
        var copy = new GenericChild();
        copy.readNbt(saved);
        assertEquals(new Battery(4, 10), copy.data());
        assertEquals(9, copy.renamedJavaField);
    }

    /**
     * One third-party adapter registration handles every use without network methods or component
     * codecs.
     */
    @Test
    void custom_registeredAdapterAndFieldOverride_areIndependent() {
        NbtSerialization.register(Foreign.class, new ForeignAdapter());
        var source = new CustomComponent();
        source.normal = new Foreign(7);
        source.compact = new Foreign(8);
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        assertInstanceOf(IntTag.class, ((CompoundTag) saved.get("normal")).get("v"));
        assertInstanceOf(StringTag.class, ((CompoundTag) saved.get("compact")).get("v"));
        var copy = new CustomComponent();
        copy.readNbt(saved);
        assertEquals(new Foreign(7), copy.normal);
        assertEquals(new Foreign(8), copy.compact);
        assertThrows(
                IllegalStateException.class,
                () -> NbtSerialization.register(Foreign.class, new ForeignAdapter()));
    }

    /**
     * Registering a concrete generic type never replaces a different specialization.
     */
    @Test
    void custom_genericRegistration_preservesTypeIdentity() {
        var token = new TypeRef<List<ForeignToken>>() {};
        NbtSerialization.register(token, new TokenListAdapter());
        var serializer = NbtSerialization.serializer(new TypeRef<List<ForeignToken>>() {});
        var value = List.of(new ForeignToken("abc"));
        assertEquals(value, serializer.read(serializer.write(value)));
        assertThrows(
                EcsSerializationException.class,
                () -> NbtSerialization.serializer(new TypeRef<List<Unregistered>>() {}));
    }

    /**
     * Failed resolution leaves type registration available so mod initialization can correct
     * adapter order.
     */
    @Test
    void schema_missingAdapter_canRegisterAndRetryBeforeFirstSuccessfulUse() {
        var failure =
                assertThrows(
                        EcsSerializationException.class,
                        () -> NbtSerialization.validateComponent(LateComponent.class));
        assertTrue(failure.getMessage().contains(".late"));
        NbtSerialization.register(
                Late.class,
                EcsBuiltinSerializers.of(
                        value -> IntTag.valueOf(value.value()),
                        tag -> new Late(NbtAccess.number((NumericTag) tag).intValue())));
        assertDoesNotThrow(() -> NbtSerialization.validateComponent(LateComponent.class));
        assertThrows(
                IllegalStateException.class,
                () ->
                        NbtSerialization.register(
                                Battery.class, NbtSerialization.serializer(Battery.class)));
    }

    /**
     * Unsupported annotations/type shapes fail before any component factory or world is used.
     */
    @ParameterizedTest
    @MethodSource("invalidComponents")
    void schema_invalidMembers_reportsClassAndRejects(
            Class<? extends AnnotatedWorldObjectComponent> type) {
        var failure =
                assertThrows(
                        EcsSerializationException.class,
                        () -> NbtSerialization.validateComponent(type));
        assertTrue(failure.getMessage().contains(type.getName()));
    }

    /**
     * @return distinct invalid field/schema declarations
     */
    private static List<Class<? extends AnnotatedWorldObjectComponent>> invalidComponents() {
        return List.of(
                StaticField.class,
                FinalField.class,
                TransientField.class,
                BlankKey.class,
                DuplicateKey.class,
                MissingPersist.class,
                RawList.class,
                WildcardList.class,
                ObjectField.class,
                WrongOverride.class,
                RecursiveComponent.class,
                UnannotatedRecordComponent.class);
    }

    /**
     * NBT primitive encodings and Minecraft wrappers retain their expected runtime value types.
     */
    @Test
    void builtins_scalarsMinecraftEnumsAndArrays_roundTrip() {
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
        roundTrip(BlockPos.class, new BlockPos(-12, 60, 15));
        roundTrip(ChunkPos.class, new ChunkPos(-4, 8));
        roundTrip(State.class, State.RUNNING);
        var section = NbtSerialization.serializer(SectionPos.class);
        var position = SectionPos.of(1, -2, 3);
        assertEquals(position.asLong(), section.read(section.write(position)).asLong());
        var identifier = dev.sixik.sdmal.SDMAdvancedLibrary.id("example");
        var codec =
                NbtSerialization.fromCodec(
                        dev.sixik.sdmal.api.ecs.codecs.ExternFieldCodecs.resourceLocation());
        assertEquals(identifier, codec.read(codec.write(identifier)));
        var integers = NbtSerialization.serializer(int[].class);
        int[] data = {1, 2};
        assertArrayEquals(data, integers.read(integers.write(data)));
        var bytes = NbtSerialization.serializer(byte[].class);
        assertArrayEquals(new byte[] {-1, 0}, bytes.read(bytes.write(new byte[] {-1, 0})));
        var longs = NbtSerialization.serializer(long[].class);
        assertArrayEquals(
                new long[] {Long.MIN_VALUE}, longs.read(longs.write(new long[] {Long.MIN_VALUE})));
        var bool = NbtSerialization.serializer(Boolean.class);
        assertThrows(EcsSerializationException.class, () -> bool.read(ByteTag.valueOf((byte) 2)));
    }

    /**
     * Duplicate map keys and bounded collection lengths are rejected instead of losing data or
     * over-allocating.
     */
    @Test
    void collections_duplicatesAndExcessiveSize_reject() {
        var serializer = NbtSerialization.serializer(new TypeRef<Map<String, Integer>>() {});
        ListTag encoded = (ListTag) serializer.write(Map.of("a", 1));
        encoded.add(encoded.get(0).copy());
        assertThrows(EcsSerializationException.class, () -> serializer.read(encoded));
        var lists = NbtSerialization.serializer(new TypeRef<List<Integer>>() {});
        assertThrows(
                EcsSerializationException.class,
                () -> lists.write(java.util.Collections.nCopies(65_537, 1)));
        assertThrows(IllegalArgumentException.class, () -> new TypeRef<List<?>>() {});
    }

    /**
     * Compound values and adapter payloads never expose aliases to an input/output archive.
     */
    @Test
    void component_mutableNbtValues_areDefensivelyCopied() {
        var source = new TagComponent();
        source.tag.putInt("a", 1);
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        var copy = new TagComponent();
        copy.readNbt(saved);
        source.tag.putInt("a", 2);
        copy.tag.putInt("a", 3);
        var another = new TagComponent();
        another.readNbt(saved);
        assertEquals(1, NbtAccess.number((NumericTag) another.tag.get("a")).intValue());
    }

    /**
     * Existing full/delta codecs can supply the NBT adapter without changing their existing wire
     * behavior.
     */
    @Test
    void adapters_existingCodec_reusesOnlyNbtCapability() {
        var adapter = NbtSerialization.fromCodec(FieldCodecs.intValue());
        assertEquals(42, adapter.read(adapter.write(42)));
        assertThrows(
                EcsSerializationException.class, () -> adapter.read(StringTag.valueOf("wrong")));
    }

    /**
     * Public migration helpers distinguish null/missing and retain exact nested generic schemas.
     */
    @Test
    void migrationHelpers_fieldEnvelopes_roundTripWithoutKnowingInternalFormat() {
        assertNull(
                NbtSerialization.readValue(
                        String.class, NbtSerialization.writeValue(String.class, null)));
        assertEquals(
                4,
                NbtSerialization.readValue(int.class, NbtSerialization.writeValue(int.class, 4)));
        assertThrows(
                EcsSerializationException.class,
                () -> NbtSerialization.writeValue(int.class, null));
        assertThrows(
                EcsSerializationException.class,
                () ->
                        NbtSerialization.readValue(
                                int.class, NbtSerialization.writeValue(Integer.class, null)));
        var type = new TypeRef<List<Battery>>() {};
        var value = List.of(new Battery(1, 5));
        assertEquals(
                value, NbtSerialization.readValue(type, NbtSerialization.writeValue(type, value)));
    }

    /**
     * Fatal JVM errors from user record constructors are never converted into recoverable data
     * failures.
     */
    @Test
    void record_fatalConstructorFailure_propagatesInsteadOfQuarantining() {
        var serializer = NbtSerialization.serializer(FatalRecord.class);
        var tag = (CompoundTag) serializer.write(new FatalRecord(false));
        ((CompoundTag) tag.get("fail")).put("v", ByteTag.valueOf(true));
        assertThrows(LinkageError.class, () -> serializer.read(tag));
    }

    /**
     * A no-argument adapter must honor the non-null payload/value contract even inside nested data.
     */
    @Test
    void adapters_invalidNullResults_rejectInsteadOfSilentlyLosingValues() {
        NbtSerialization.register(Broken.class, new BrokenAdapter());
        var type = NbtSerialization.serializer(Broken.class);
        assertThrows(EcsSerializationException.class, () -> type.write(new Broken()));
        assertThrows(EcsSerializationException.class, () -> type.read(IntTag.valueOf(1)));
    }

    /**
     * Archive migration runs before annotated decoding, and malformed values preserve the whole raw
     * object.
     */
    @Test
    void worldArchive_annotationsIntegrateWithMigrationAndQuarantine() {
        String id = "test:annotated_" + UUID.randomUUID();
        var registry = WorldObjectComponentRegistry.instance();
        var type =
                registry.register(
                        new WorldObjectComponentType<>(
                                id,
                                MigratingComponent.class,
                                WorldObjectComponentType.DuplicatePolicy.REJECT,
                                MigratingComponent::new));
        try (var source = new WorldObjectManager();
                var restored = new WorldObjectManager();
                var rejected = new WorldObjectManager()) {
            var object = source.create();
            var component = object.attach(new MigratingComponent(), type);
            component.energy = 7;
            CompoundTag saved = source.writeNbt();
            var objectRecord = (CompoundTag) ((ListTag) saved.get("objects")).get(0);
            var componentRecord = (CompoundTag) ((ListTag) objectRecord.get("components")).get(0);
            componentRecord.putInt("version", 1);
            var data = (CompoundTag) componentRecord.get("data");
            data.remove("energy");
            data.putInt("old_energy", 19);
            data.putString("extension", "retain");
            assertTrue(restored.readNbt(saved).isEmpty());
            assertEquals(
                    19,
                    restored.get(object.id()).orElseThrow().getSingle(type).orElseThrow().energy);
            CompoundTag next = restored.writeNbt();
            var nextObject = (CompoundTag) ((ListTag) next.get("objects")).get(0);
            var nextComponent = (CompoundTag) ((ListTag) nextObject.get("components")).get(0);
            var nextData = (CompoundTag) nextComponent.get("data");
            assertNull(nextData.get("old_energy"));
            assertEquals("retain", NbtAccess.string(nextData.get("extension"), ""));
            nextData.putString("energy", "broken envelope");
            assertEquals(1, rejected.readNbt(next).size());
            assertEquals(0, rejected.size());
            assertEquals(nextObject, ((ListTag) rejected.writeNbt().get("objects")).get(0));
        } finally {
            registry.unregister(id);
        }
    }

    /**
     * Registration validates schemas eagerly and never installs a malformed component descriptor.
     */
    @Test
    void registry_invalidAnnotatedComponent_rejectsBeforeInstallation() {
        var registry = WorldObjectComponentRegistry.instance();
        String id = "test:invalid_" + UUID.randomUUID();
        var type =
                new WorldObjectComponentType<>(
                        id,
                        ObjectField.class,
                        WorldObjectComponentType.DuplicatePolicy.REJECT,
                        ObjectField::new);
        assertThrows(EcsSerializationException.class, () -> registry.register(type));
        assertTrue(registry.get(id).isEmpty());
    }

    /**
     * Attached component persistence follows manager ownership even when callers bypass
     * manager.writeNbt.
     *
     * @throws Exception if the worker cannot complete
     */
    @Test
    void component_attachedOutsideOwnerThread_rejectsPersistence() throws Exception {
        try (var manager = new WorldObjectManager()) {
            var component = manager.create().attach(new PlainComponent());
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(
                                () -> {
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> component.writeNbt(new CompoundTag()));
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> component.readNbt(new CompoundTag()));
                                })
                        .get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Verifies a concrete scalar with exact equality; floating-point fixtures have exact binary
     * representations.
     *
     * @param type concrete value class
     * @param value expected non-null value
     * @param <T> fixture value type
     */
    private static <T> void roundTrip(Class<T> type, T value) {
        var serializer = NbtSerialization.serializer(type);
        assertEquals(value, serializer.read(serializer.write(value)));
    }

    /**
     * Primitive/private component fixture with constructor defaults and an unpersisted cache.
     */
    private static final class PlainComponent extends AnnotatedWorldObjectComponent {
        /**
         * Remaining energy, initially 100 units.
         */
        @EcsPersist("energy")
        private int energy = 100;

        /**
         * Nullable owning identity.
         */
        @EcsPersist("owner")
        private UUID owner = new UUID(0, 1);

        /**
         * Nullable nested record.
         */
        @EcsPersist("battery")
        private Battery battery = new Battery(0, 100);

        /**
         * Derived cache deliberately excluded from persistence.
         */
        private int cached;
    }

    /**
     * Validated immutable custom data requiring no hand-written adapter.
     *
     * @param stored stored units in [0, capacity]
     * @param capacity non-negative capacity
     */
    @EcsData
    private record Battery(@EcsPersist("stored") int stored, @EcsPersist("capacity") int capacity) {
        /**
         * Rejects inconsistent saved state via the normal canonical constructor.
         *
         * @param stored stored units
         * @param capacity maximum units
         */
        private Battery {
            if (stored < 0 || capacity < stored)
                throw new IllegalArgumentException("Invalid battery state");
        }
    }

    /**
     * Generic record fixture retaining its concrete nested argument.
     *
     * @param value wrapped value
     * @param <T> concrete generic payload
     */
    @EcsData
    private record Box<T>(@EcsPersist("value") T value) {}

    /**
     * Nested generic collection fixture.
     */
    private static final class GenericComponent extends AnnotatedWorldObjectComponent {
        /**
         * Nullable record elements.
         */
        @EcsPersist("batteries")
        private List<Battery> batteries = new ArrayList<>();

        /**
         * UUID-keyed nested specialized records.
         */
        @EcsPersist("owners")
        private Map<UUID, Optional<Box<List<UUID>>>> owners = new LinkedHashMap<>();

        /**
         * An empty optional, distinct from null.
         */
        @EcsPersist("empty")
        private Optional<String> empty = Optional.of("initial");

        /**
         * A nullable optional reference.
         */
        @EcsPersist("absent")
        private Optional<String> absent = Optional.empty();
    }

    /**
     * Inherited generic component storage.
     *
     * @param <T> specialized by a concrete component subclass
     */
    private abstract static class GenericBase<T> extends AnnotatedWorldObjectComponent {
        /**
         * Private value whose generic type is resolved from the concrete subclass.
         */
        @EcsPersist("inherited")
        private T data;

        /**
         * Assigns the fixture payload.
         *
         * @param value new data
         */
        final void setData(T value) {
            data = value;
        }

        /**
         * @return inherited fixture value
         */
        final T data() {
            return data;
        }
    }

    /**
     * Concrete inherited generic specialization.
     */
    private static final class GenericChild extends GenericBase<Battery> {
        /**
         * Java name deliberately differs from the persistent key.
         */
        @EcsPersist("stable")
        private int renamedJavaField;
    }

    /**
     * Third-party-like type without structural opt-in.
     *
     * @param value payload
     */
    private record Foreign(int value) {}

    /**
     * NBT-only adapter requiring no network/copy/equality implementation.
     */
    private static final class ForeignAdapter implements EcsSerializer<Foreign> {
        /**
         * Writes an integer payload.
         *
         * @param value foreign value
         * @return integer tag
         */
        @Override
        public Tag write(Foreign value) {
            return IntTag.valueOf(value.value());
        }

        /**
         * Reads an integer payload.
         *
         * @param tag owned payload
         * @return reconstructed foreign value
         */
        @Override
        public Foreign read(Tag tag) {
            return new Foreign(NbtAccess.number((IntTag) tag).intValue());
        }
    }

    /**
     * Field-local alternate representation of the same third-party type.
     */
    public static final class CompactAdapter implements EcsSerializer<Foreign> {
        /**
         * Creates a stateless annotation adapter.
         */
        public CompactAdapter() {}

        /**
         * Writes textual decimal data.
         *
         * @param value source foreign value
         * @return decimal string
         */
        @Override
        public Tag write(Foreign value) {
            return StringTag.valueOf(Integer.toString(value.value()));
        }

        /**
         * Reads textual decimal data.
         *
         * @param tag source string tag
         * @return reconstructed value
         */
        @Override
        public Foreign read(Tag tag) {
            return new Foreign(Integer.parseInt(NbtAccess.string((StringTag) tag)));
        }
    }

    /**
     * Global adapter and field-local override fixture.
     */
    private static final class CustomComponent extends AnnotatedWorldObjectComponent {
        /**
         * Globally registered representation.
         */
        @EcsPersist("normal")
        private Foreign normal;

        /**
         * Field-local textual representation.
         */
        @EcsPersist("compact")
        @EcsUseAdapter(CompactAdapter.class)
        private Foreign compact;
    }

    /**
     * Unsupported leaf whose enclosing list has an explicitly registered adapter.
     *
     * @param text token payload
     */
    private record ForeignToken(String text) {}

    /**
     * Specializes a whole generic collection with an application-defined compact format.
     */
    private static final class TokenListAdapter implements EcsSerializer<List<ForeignToken>> {
        /**
         * Writes this fixture's single token.
         *
         * @param value singleton fixture list
         * @return token string
         */
        @Override
        public Tag write(List<ForeignToken> value) {
            return StringTag.valueOf(value.get(0).text());
        }

        /**
         * Reads a singleton list.
         *
         * @param tag token string
         * @return reconstructed list
         */
        @Override
        public List<ForeignToken> read(Tag tag) {
            return List.of(new ForeignToken(NbtAccess.string((StringTag) tag)));
        }
    }

    /**
     * Unsupported leaf without an adapter or EcsData opt-in.
     */
    private record Unregistered() {}

    /**
     * Registered after an initially failed schema validation.
     *
     * @param value integer payload
     */
    private record Late(int value) {}

    /**
     * Retryable initialization failure fixture.
     */
    private static final class LateComponent extends AnnotatedWorldObjectComponent {
        /**
         * Value requiring a later registration.
         */
        @EcsPersist("late")
        private Late late;
    }

    /**
     * Named enum values are stable across ordinal changes.
     */
    private enum State {
        /**
         * Running state.
         */
        RUNNING
    }

    /**
     * Mutable NBT payload fixture.
     */
    private static final class TagComponent extends AnnotatedWorldObjectComponent {
        /**
         * Owned nested mutable compound.
         */
        @EcsPersist("tag")
        private CompoundTag tag = new CompoundTag();
    }

    /**
     * Component exercising the existing component-version migration contract.
     */
    private static final class MigratingComponent extends AnnotatedWorldObjectComponent {
        /**
         * Current energy amount in arbitrary units.
         */
        @EcsPersist("energy")
        private int energy;

        /**
         * @return schema version two
         */
        @Override
        public int dataVersion() {
            return 2;
        }

        /**
         * Converts a legacy plain scalar into the annotation format's explicit envelope.
         *
         * @param version source schema version
         * @param nbt owned prior payload
         * @return migrated payload
         */
        @Override
        public CompoundTag migrateNbt(int version, CompoundTag nbt) {
            if (version != 1) throw new EcsSerializationException("Unsupported fixture version");
            int value = NbtAccess.number((IntTag) nbt.get("old_energy")).intValue();
            nbt.remove("old_energy");
            nbt.put("energy", NbtSerialization.writeValue(Integer.class, value));
            return nbt;
        }
    }

    /**
     * Constructor used to verify fatal failure propagation.
     *
     * @param fail whether construction should throw a fatal class linkage failure
     */
    @EcsData
    private record FatalRecord(@EcsPersist("fail") boolean fail) {
        /**
         * Rejects the explicit failing state.
         *
         * @param fail true to simulate a fatal runtime failure
         */
        private FatalRecord {
            if (fail) throw new LinkageError("Fixture fatal failure");
        }
    }

    /**
     * Invalid adapter leaf fixture.
     */
    private record Broken() {}

    /**
     * Intentionally violates both adapter contracts to exercise strict wrappers.
     */
    private static final class BrokenAdapter implements EcsSerializer<Broken> {
        /**
         * Violates the non-null payload contract.
         *
         * @param value fixture value
         * @return null deliberately
         */
        @Override
        public Tag write(Broken value) {
            return null;
        }

        /**
         * Violates the non-null decoded-value contract.
         *
         * @param tag fixture payload
         * @return null deliberately
         */
        @Override
        public Broken read(Tag tag) {
            return null;
        }
    }

    /**
     * Invalid static state is not per-component.
     */
    private static final class StaticField extends AnnotatedWorldObjectComponent {
        /**
         * Invalid static member.
         */
        @EcsPersist("bad")
        private static int value;
    }

    /**
     * Final component fields cannot be restored.
     */
    private static final class FinalField extends AnnotatedWorldObjectComponent {
        /**
         * Invalid final member.
         */
        @EcsPersist("bad")
        private final int value = 1;
    }

    /**
     * Transient and persist annotations conflict.
     */
    private static final class TransientField extends AnnotatedWorldObjectComponent {
        /**
         * Invalid transient member.
         */
        @EcsPersist("bad")
        private transient int value;
    }

    /**
     * Blank keys are not a stable schema.
     */
    private static final class BlankKey extends AnnotatedWorldObjectComponent {
        /**
         * Invalid empty key.
         */
        @EcsPersist(" ")
        private int value;
    }

    /**
     * Two fields may not share one persisted key.
     */
    private static final class DuplicateKey extends AnnotatedWorldObjectComponent {
        /**
         * First conflicting field.
         */
        @EcsPersist("same")
        private int first;

        /**
         * Second conflicting field.
         */
        @EcsPersist("same")
        private int second;
    }

    /**
     * Adapter selection requires persistence opt-in.
     */
    private static final class MissingPersist extends AnnotatedWorldObjectComponent {
        /**
         * Invalid annotation combination.
         */
        @EcsUseAdapter(CompactAdapter.class)
        private Foreign value;
    }

    /**
     * Raw collections erase their element schema.
     */
    private static final class RawList extends AnnotatedWorldObjectComponent {
        /**
         * Invalid raw generic member.
         */
        @SuppressWarnings("rawtypes")
        @EcsPersist("raw")
        private List value;
    }

    /**
     * Wildcards cannot define a concrete on-disk representation.
     */
    private static final class WildcardList extends AnnotatedWorldObjectComponent {
        /**
         * Invalid wildcard element.
         */
        @EcsPersist("unknown")
        private List<?> value;
    }

    /**
     * Unrestricted Object graphs have no known value representation.
     */
    private static final class ObjectField extends AnnotatedWorldObjectComponent {
        /**
         * Unsupported arbitrary object.
         */
        @EcsPersist("object")
        private Object value;
    }

    /**
     * An override must match the exact member type.
     */
    private static final class WrongOverride extends AnnotatedWorldObjectComponent {
        /**
         * Foreign adapter cannot encode an integer.
         */
        @EcsPersist("wrong")
        @EcsUseAdapter(CompactAdapter.class)
        private int value;
    }

    /**
     * Recursive schemas require an explicit application adapter.
     *
     * @param children recursive data
     */
    @EcsData
    private record Recursive(@EcsPersist("children") List<Recursive> children) {}

    /**
     * Recursive graph fixture.
     */
    private static final class RecursiveComponent extends AnnotatedWorldObjectComponent {
        /**
         * Unsupported automatic recursive schema.
         */
        @EcsPersist("recursive")
        private Recursive value;
    }

    /**
     * Structural records require explicit stable keys on every constructor argument.
     *
     * @param value deliberately missing its persistence annotation
     */
    @EcsData
    private record Incomplete(int value) {}

    /**
     * Incomplete record schema fixture.
     */
    private static final class UnannotatedRecordComponent extends AnnotatedWorldObjectComponent {
        /**
         * Record missing an explicit stable key.
         */
        @EcsPersist("incomplete")
        private Incomplete value;
    }
}
