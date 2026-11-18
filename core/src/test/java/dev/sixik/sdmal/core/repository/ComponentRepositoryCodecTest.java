package dev.sixik.sdmal.core.repository;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.ComponentRegistry;
import dev.sixik.sdmal.api.ecs.ComponentType;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import dev.sixik.sdmal.api.ecs.sync.Field;
import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.api.ecs.sync.NetworkComponent;
import dev.sixik.sdmal.api.repository.*;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Verifies component identity, schema migrations, owner-thread access, and real local backends.
 */
class ComponentRepositoryCodecTest {
    /**
     * Independent file and embedded SQL paths for each test.
     */
    @TempDir Path directory;

    /**
     * Native Minecraft serialization bootstrap and component service equivalents for fixtures.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ComponentRegistry.instance().registerAnnotated(StoredComponent.class);
        ComponentRegistry.instance().registerAnnotated(OtherComponent.class);
        ComponentRegistry.instance().registerAnnotated(TrackedComponent.class);
    }

    /**
     * Only persistence data is copied; live ownership, callbacks and EcsSync-only fields stay local.
     */
    @Test
    void roundTrip_attachedComponent_preservesDataWithoutOwnershipOrRuntimeState() {
        var codec = RepositoryCodec.component(StoredComponent.class);
        try (var ecs = new EcsObjectManager()) {
            var source = ecs.create().attach(new StoredComponent());
            source.id = "shop_one";
            source.energy = Long.MAX_VALUE;
            source.visual = 99;
            source.local = 77;
            source.values = new int[] {4, 5};
            CompoundTag stored = codec.writeNbt(source);
            assertEquals("sdmal_test:repository_component", NbtAccess.string(stored.get("type"), ""));
            assertEquals(2, NbtAccess.number((NumericTag) stored.get("version")).intValue());

            var restored = codec.readNbt(stored);
            assertNotSame(source, restored);
            assertTrue(restored.owner().isEmpty());
            assertEquals("shop_one", restored.id);
            assertEquals(Long.MAX_VALUE, restored.energy);
            assertArrayEquals(new int[] {4, 5}, restored.values);
            assertEquals(7, restored.visual);
            assertEquals(11, restored.local);
            assertEquals(0, restored.attachCallbacks);
            assertEquals(1, source.attachCallbacks);

            CompoundTag before = stored.copy();
            restored.values[0] = 100;
            assertArrayEquals(new int[] {4, 5}, source.values);
            assertEquals(before, stored);
            assertArrayEquals(new int[] {4, 5}, codec.readNbt(stored).values);
        }
    }

    /**
     * Registry resolution is deferred; creating a definition does not create component instances.
     */
    @Test
    void factory_beforeRegistration_resolvesOnUseAndRejectsAfterUnregister() {
        int initial = LazyComponent.constructions;
        var codec = RepositoryCodec.component(LazyComponent.class);
        var descriptor = new ComponentType<>("sdmal_test:repository_lazy", LazyComponent.class,
                ComponentType.DuplicatePolicy.REJECT, LazyComponent::new);
        assertEquals(initial, LazyComponent.constructions);
        assertThrows(RepositoryException.class, () -> codec.writeNbt(new LazyComponent()));
        ComponentRegistry.instance().register(descriptor);
        CompoundTag encoded;
        try {
            var source = new LazyComponent();
            source.value = 19;
            int beforeDecode = LazyComponent.constructions;
            encoded = codec.writeNbt(source);
            assertEquals(beforeDecode, LazyComponent.constructions);
            assertEquals(19, codec.readNbt(encoded).value);
            assertEquals(beforeDecode + 1, LazyComponent.constructions);
        } finally {
            ComponentRegistry.instance().unregister(descriptor.id());
        }
        assertThrows(RepositoryException.class, () -> codec.readNbt(encoded));
    }

    /**
     * Migrations receive a private payload and the input envelope remains unchanged.
     */
    @Test
    void read_oldVersion_migratesAndResavesCurrentVersion() {
        var codec = RepositoryCodec.component(StoredComponent.class);
        var source = new StoredComponent();
        source.energy = 125;
        CompoundTag encoded = codec.writeNbt(source);
        encoded.putInt("version", 1);
        CompoundTag data = (CompoundTag) encoded.get("data");
        data.put("legacy_energy", data.get("energy"));
        data.remove("energy");
        CompoundTag original = encoded.copy();

        var restored = codec.readNbt(encoded);
        assertEquals(125, restored.energy);
        assertEquals(original, encoded);
        assertEquals(2, NbtAccess.number((NumericTag) codec.writeNbt(restored).get("version")).intValue());
        assertFalse(((CompoundTag) codec.writeNbt(restored).get("data")).contains("legacy_energy"));
    }

    /**
     * Unknown payload keys do not mutate the input; unsupported migrations remain visible.
     */
    @Test
    void read_unknownPayloadAndUnsupportedMigration_keepsInputAndPropagatesFailure() {
        var codec = RepositoryCodec.component(StoredComponent.class);
        CompoundTag envelope = codec.writeNbt(new StoredComponent());
        ((CompoundTag) envelope.get("data")).putString("extension", "preserve_me");
        CompoundTag original = envelope.copy();
        var restored = codec.readNbt(envelope);
        assertEquals("default", restored.id);
        assertEquals(original, envelope);
        envelope.putInt("version", 0);
        CompoundTag before = envelope.copy();
        RepositoryException failure = assertThrows(RepositoryException.class, () -> codec.readNbt(envelope));
        assertInstanceOf(dev.sixik.sdmal.api.ecs.EcsPersistenceException.class, failure.getCause());
        assertEquals(before, envelope);
    }

    /**
     * Metadata is validated independently of the component payload's own NBT schema.
     * @param malformed metadata corruption to apply
     */
    @ParameterizedTest
    @ValueSource(strings = {"type", "future_format", "negative_format", "missing_format",
            "future_version", "negative_version", "wrong_version_type", "missing_data", "wrong_data"})
    void read_invalidEnvelope_rejectsWithoutMutatingInput(String malformed) {
        var codec = RepositoryCodec.component(StoredComponent.class);
        CompoundTag envelope = codec.writeNbt(new StoredComponent());
        switch (malformed) {
            case "type" -> envelope.putString("type", "sdmal_test:repository_other");
            case "future_format" -> envelope.putInt("format", 2);
            case "negative_format" -> envelope.putInt("format", -1);
            case "missing_format" -> envelope.remove("format");
            case "future_version" -> envelope.putInt("version", 3);
            case "negative_version" -> envelope.putInt("version", -1);
            case "wrong_version_type" -> envelope.putLong("version", 2);
            case "missing_data" -> envelope.remove("data");
            case "wrong_data" -> envelope.putString("data", "bad");
            default -> throw new AssertionError(malformed);
        }
        CompoundTag before = envelope.copy();
        assertThrows(RepositoryException.class, () -> codec.readNbt(envelope));
        assertEquals(before, envelope);
    }

    /**
     * Declared abstract/generic types and subclasses under another declared schema are rejected.
     */
    @Test
    void factory_inexactTypes_rejectsAbstractGenericAndRuntimeSubclass() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryCodec.component(Component.class));
        assertThrows(IllegalArgumentException.class, () -> RepositoryCodec.component(GenericComponent.class));
        var codec = RepositoryCodec.component(StoredComponent.class);
        assertThrows(RepositoryException.class, () -> codec.writeNbt(new DerivedComponent()));
        assertThrows(NullPointerException.class, () -> RepositoryCodec.component(null));
    }

    /**
     * Attached access is checked even when an override would omit the base class's thread check.
     */
    @Test
    void write_wrongOwnerThread_rejectsBeforeSerializing() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var ecs = new EcsObjectManager()) {
            var attached = ecs.create().attach(new StoredComponent());
            var codec = RepositoryCodec.component(StoredComponent.class);
            var result = worker.submit(() -> codec.writeNbt(attached));
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * An already attached factory result is never passed into readNbt.
     */
    @Test
    void read_attachedFactoryResult_rejectsWithoutChangingExistingComponent() {
        var descriptor = new ComponentType<>("sdmal_test:repository_bad_factory", BadFactoryComponent.class,
                ComponentType.DuplicatePolicy.REJECT, () -> BadFactoryComponent.existing);
        ComponentRegistry.instance().register(descriptor);
        try (var ecs = new EcsObjectManager()) {
            BadFactoryComponent.existing = ecs.create().attach(new BadFactoryComponent());
            var codec = RepositoryCodec.component(BadFactoryComponent.class);
            var stored = codec.writeNbt(BadFactoryComponent.existing);
            assertThrows(RepositoryException.class, () -> codec.readNbt(stored));
            assertFalse(BadFactoryComponent.existing.read);
        } finally {
            BadFactoryComponent.existing = null;
            ComponentRegistry.instance().unregister(descriptor.id());
        }
    }

    /**
     * The NetworkComponent field/tracked persistence contract is reused instead of annotations.
     */
    @Test
    void roundTrip_networkComponent_restoresExplicitPersistedFields() {
        var codec = RepositoryCodec.component(TrackedComponent.class);
        var source = new TrackedComponent();
        source.amount.set(42);
        var restored = codec.decode(codec.encode(source));
        assertEquals(42, restored.amount.get());
        assertFalse(restored.isAttached());
    }

    /**
     * A component can be stored directly and reopened through any local repository backend.
     * @param backend JSON, native NBT or real H2 SQL
     */
    @ParameterizedTest
    @ValueSource(strings = {"json", "nbt", "h2"})
    void repository_directComponent_roundTripsAndReopens(String backend) {
        var definition = new RepositoryDefinition<String, StoredComponent>(
                id -> id, id -> id, component -> component.id,
                RepositoryCodec.component(StoredComponent.class));
        var source = new StoredComponent();
        source.id = "stored_shop";
        source.energy = 900;
        try (var manager = manager(backend);
                var repository = manager.open("components", definition)) {
            repository.upsert(source.id, source);
            assertEquals(900, repository.find(source.id).orElseThrow().energy);
        }
        try (var manager = manager(backend);
                var repository = manager.open("components", definition)) {
            var restored = repository.findAll().get(source.id);
            assertNotNull(restored);
            assertEquals(900, restored.energy);
            assertFalse(restored.isAttached());
        }
    }

    /**
     * Selects a backend sharing the same persistent path across reopen operations.
     * @param backend local backend selector
     * @return manager owned by the test
     */
    private RepositoryManager manager(String backend) {
        return switch (backend) {
            case "json" -> Repositories.json(directory);
            case "nbt" -> Repositories.nbt(directory);
            case "h2" -> Repositories.h2("jdbc:h2:file:" + directory.resolve("components"));
            default -> throw new AssertionError(backend);
        };
    }

    /**
     * Annotated persistence fixture with local and network-only state.
     */
    @EcsComponent(id = "sdmal_test:repository_component")
    public static class StoredComponent extends Component {
        /**
         * Persisted repository identity.
         */
        @EcsPersist("id") private String id = "default";
        /**
         * Persisted exact signed energy value.
         */
        @EcsPersist("energy") private long energy;
        /**
         * Persisted primitive array with independent ownership.
         */
        @EcsPersist("values") private int[] values = {1, 2};
        /**
         * Visual state selected only for network snapshots.
         */
        @EcsSync("visual") private int visual = 7;
        /**
         * Unpersisted runtime value.
         */
        private int local = 11;
        /**
         * Number of ownership callbacks, never persisted.
         */
        private int attachCallbacks;
        /**
         * Creates defaults for registered restoration.
         */
        public StoredComponent() {}
        /**
         * @return current persisted version
         */
        @Override public int dataVersion() { return 2; }
        /**
         * Counts only real attachment, not repository decoding.
         */
        @Override public void onAttached() { attachCallbacks++; }
        /**
         * Migrates the old energy property using the codec-owned payload.
         * @param version stored schema version
         * @param data owned old payload
         * @return migrated payload
         */
        @Override public CompoundTag migrateNbt(int version, CompoundTag data) {
            if (version != 1) return super.migrateNbt(version, data);
            data.put("energy", data.get("legacy_energy").copy());
            data.remove("legacy_energy");
            return data;
        }
    }

    /**
     * Runtime subtype that cannot be serialized using its parent's declared codec.
     */
    private static final class DerivedComponent extends StoredComponent {}

    /**
     * Distinct registered type, used to reject an envelope from another component.
     */
    @EcsComponent(id = "sdmal_test:repository_other")
    public static final class OtherComponent extends Component {
        /**
         * Creates a stateless registered fixture.
         */
        public OtherComponent() {}
    }

    /**
     * Unbound generic class cannot identify a complete component schema.
     * @param <T> unbound value type
     */
    private static class GenericComponent<T> extends Component {}

    /**
     * Manual registration fixture used to verify lazy lookup and exact factory invocation.
     */
    public static final class LazyComponent extends Component {
        /**
         * Total fixture constructor calls.
         */
        private static int constructions;
        /**
         * Persisted fixture value.
         */
        @EcsPersist("value") private int value;
        /**
         * Counts construction.
         */
        public LazyComponent() { constructions++; }
    }

    /**
     * Invalid factory returns an existing owned component.
     */
    public static final class BadFactoryComponent extends Component {
        /**
         * Shared instance deliberately violating the factory contract.
         */
        private static BadFactoryComponent existing;
        /**
         * Whether a decoder touched this existing live component.
         */
        private boolean read;
        /**
         * Creates the intentionally invalid factory fixture.
         */
        public BadFactoryComponent() {}
        /**
         * Records unwanted mutation; correct code rejects before invoking it.
         * @param data ignored fixture payload
         */
        @Override public void readNbt(CompoundTag data) { read = true; }
    }

    /**
     * Explicit handle persistence remains available through the same component codec.
     */
    @EcsComponent(id = "sdmal_test:repository_tracked")
    public static final class TrackedComponent extends NetworkComponent {
        /**
         * NBT-only field declared through the tracked-component API.
         */
        private final Field<Integer> amount = field("amount", FieldCodecs.intValue(), 0);
        /**
         * Creates a registered field schema.
         */
        public TrackedComponent() {}
    }
}
