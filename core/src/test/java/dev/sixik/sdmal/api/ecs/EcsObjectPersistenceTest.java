package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.api.ecs.sync.NetworkComponent;
import dev.sixik.sdmal.api.ecs.sync.TrackedField;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Verifies archive identity, scheduling, unknown-data preservation and staged lifecycle
 * restoration.
 */
class EcsObjectPersistenceTest {
    /**
     * Registry IDs owned by this test instance and removed after each case.
     */
    private final List<String> registrations = new ArrayList<>();

    /**
     * Initializes vanilla codec registries used by real NBT values.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Removes fixture types so registry state cannot leak into other tests.
     */
    @AfterEach
    void cleanup() {
        registrations.forEach(id -> ComponentRegistry.instance().unregister(id));
    }

    /**
     * UUIDs, order, state, manager time and the remaining dispatch delay survive restoration.
     */
    @Test
    void archive_roundTrip_preservesIdentityStateAndSchedule() {
        var type = type(Counter.class, Counter::new);
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            EcsObject first = source.create();
            Counter counter = first.attach(new Counter(), type);
            counter.value.set(10);
            first.setTickInterval(3);
            EcsObject sleeper = source.create();
            sleeper.attach(new Counter(), type).value.set(50);
            sleeper.sleep();
            source.tickAll();
            source.tickAll();
            CompoundTag saved = source.writeNbt();
            assertTrue(restored.readNbt(saved).isEmpty());
            assertEquals(
                    List.of(first.id(), sleeper.id()),
                    restored.snapshot().stream().map(EcsObject::id).toList());
            EcsObject copy = restored.get(first.id()).orElseThrow();
            Counter value = copy.getSingle(type).orElseThrow();
            assertEquals(11, value.value.get());
            assertEquals(3, copy.tickInterval());
            assertTrue(restored.get(sleeper.id()).orElseThrow().isSleeping());
            assertEquals(2, restored.lastTick());
            restored.tickAll();
            assertEquals(11, value.value.get());
            restored.tickAll();
            assertEquals(12, value.value.get());
        }
    }

    /**
     * Repeated components retain their attach sequences and do not replace one another on load.
     */
    @Test
    void archive_repeatedType_keepsEveryComponent() {
        var type =
                new ComponentType<>(
                        id(),
                        Counter.class,
                        ComponentType.DuplicatePolicy.ALLOW_MULTIPLE,
                        Counter::new);
        ComponentRegistry.instance().register(type);
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            EcsObject object = source.create();
            object.attach(new Counter(), type).value.set(10);
            object.attach(new Counter(), type).value.set(20);
            restored.readNbt(source.writeNbt());
            var copies = restored.get(object.id()).orElseThrow().get(type);
            assertEquals(
                    List.of(10, 20),
                    copies.stream().map(component -> component.value.get()).toList());
        }
    }

    /**
     * Every object is registered and has loaded field values before any restored onAttached
     * callback.
     */
    @Test
    void archive_attachmentCallbacks_observeCompleteWorldMembership() {
        List<Integer> observations = new ArrayList<>();
        boolean[] restoring = {false};
        Consumer<Probe> inspect =
                component -> {
                    if (restoring[0]) {
                        var manager = component.owner().orElseThrow().manager().orElseThrow();
                        assertEquals(2, manager.size());
                        assertEquals(2, manager.query(Probe.class).size());
                        assertThrows(IllegalStateException.class, manager::tickAll);
                        assertThrows(IllegalStateException.class, manager::writeNbt);
                        observations.add(component.value.get());
                    }
                };
        var type = type(Probe.class, () -> new Probe(inspect));
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            source.create().attach(new Probe(inspect), type).value.set(7);
            source.create().attach(new Probe(inspect), type).value.set(8);
            CompoundTag saved = source.writeNbt();
            restoring[0] = true;
            restored.addListener(
                    new EcsObjectManager.Listener() {
                        /**
                         * Checks the same guard during the earlier membership notification phase.
                         *
                         * @param manager restored manager
                         * @param object published object
                         */
                        @Override
                        public void onObjectAdded(EcsObjectManager manager, EcsObject object) {
                            assertThrows(IllegalStateException.class, manager::tickAll);
                            assertThrows(IllegalStateException.class, manager::writeNbt);
                        }
                    });
            assertTrue(restored.readNbt(saved).isEmpty());
            assertEquals(List.of(7, 8), observations);
            restored.tickAll();
            assertEquals(1, restored.lastTick());
            assertDoesNotThrow(restored::writeNbt);
        }
    }

    /**
     * Unknown types keep the whole object inactive and preserve its exact raw record across another
     * save.
     */
    @Test
    void archive_missingType_preservesWholeObjectAndReservesUuid() {
        var type = type(Counter.class, Counter::new);
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager();
                var recovered = new EcsObjectManager()) {
            UUID first = source.create().id();
            source.get(first).orElseThrow().attach(new Counter(), type).value.set(7);
            UUID second = source.create().id();
            source.get(second).orElseThrow().attach(new Counter(), type).value.set(9);
            CompoundTag saved = source.writeNbt();
            CompoundTag raw = object(saved, 0);
            String missing = id();
            component(raw, 0).putString("type", missing);
            raw.putString("unknown_object_extension", "keep me");
            CompoundTag expected = raw.copy();
            assertEquals(1, restored.readNbt(saved).size());
            assertEquals(1, restored.size());
            assertTrue(restored.get(first).isEmpty());
            assertTrue(restored.get(second).isPresent());
            assertEquals(java.util.Set.of(first), restored.unresolvedObjectIds());
            assertFalse(restored.add(new EcsObject(first)));
            raw.putString("unknown_object_extension", "caller changed original");
            CompoundTag resaved = restored.writeNbt();
            assertEquals(expected, object(resaved, 0));
            ComponentRegistry.instance()
                    .register(
                            new ComponentType<>(
                                    missing,
                                    Counter.class,
                                    ComponentType.DuplicatePolicy.REJECT,
                                    Counter::new));
            assertTrue(recovered.readNbt(resaved).isEmpty());
            assertEquals(
                    7,
                    recovered
                            .get(first)
                            .orElseThrow()
                            .getSingle(Counter.class)
                            .orElseThrow()
                            .value
                            .get());
        }
    }

    /**
     * Unknown root, object and known-component payload keys are preserved by snapshots.
     */
    @Test
    void
            archive_extensionsAndRemovedObject_preservesUnknownFieldsWithoutResurrectingDeletedMembers() {
        var type = type(Counter.class, Counter::new);
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            UUID first = source.create().id();
            source.get(first).orElseThrow().attach(new Counter(), type);
            UUID second = source.create().id();
            source.get(second).orElseThrow().attach(new Counter(), type);
            CompoundTag saved = source.writeNbt();
            saved.putString("future_root", "root");
            object(saved, 0).putString("future_object", "object");
            CompoundTag data = (CompoundTag) component(object(saved, 0), 0).get("data");
            data.putString("future_payload", "payload");
            restored.readNbt(saved);
            restored.remove(second);
            CompoundTag resaved = restored.writeNbt();
            assertEquals("root", NbtAccess.string(resaved.get("future_root"), null));
            assertEquals(1, ((ListTag) resaved.get("objects")).size());
            assertEquals("object", NbtAccess.string(object(resaved, 0).get("future_object"), null));
            assertEquals(
                    "payload",
                    NbtAccess.string(
                            ((CompoundTag) component(object(resaved, 0), 0).get("data"))
                                    .get("future_payload"),
                            null));
        }
    }

    /**
     * A newer component payload is never read or migrated by an older implementation.
     */
    @Test
    void archive_futureComponentVersion_preservesWithoutActivating() {
        var type = type(Counter.class, Counter::new);
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            source.create().attach(new Counter(), type);
            CompoundTag saved = source.writeNbt();
            component(object(saved, 0), 0).putInt("version", 2);
            assertFalse(restored.readNbt(saved).isEmpty());
            assertEquals(0, restored.size());
            assertEquals(object(saved, 0), object(restored.writeNbt(), 0));
        }
    }

    /**
     * Older component schemas migrate before readNbt and are saved with the current version.
     */
    @Test
    void archive_oldComponentVersion_migratesBeforeAttach() {
        var firstType = type(Counter.class, Counter::new);
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            UUID id = source.create().id();
            source.get(id).orElseThrow().attach(new Counter(), firstType).value.set(7);
            CompoundTag saved = source.writeNbt();
            var payload = (CompoundTag) component(object(saved, 0), 0).get("data");
            payload.putString("obsolete", "remove during migration");
            payload.putString("extension", "keep during migration");
            ComponentRegistry.instance().unregister(firstType.id());
            var upgraded =
                    new ComponentType<>(
                            firstType.id(),
                            UpgradedCounter.class,
                            ComponentType.DuplicatePolicy.REJECT,
                            UpgradedCounter::new);
            ComponentRegistry.instance().register(upgraded);
            assertTrue(restored.readNbt(saved).isEmpty());
            assertEquals(
                    107,
                    restored.get(id).orElseThrow().getSingle(upgraded).orElseThrow().value.get());
            var upgradedRecord = component(object(restored.writeNbt(), 0), 0);
            assertEquals(
                    2, NbtAccess.number((NumericTag) upgradedRecord.get("version")).intValue());
            var upgradedPayload = (CompoundTag) upgradedRecord.get("data");
            assertNull(upgradedPayload.get("obsolete"));
            assertEquals(
                    "keep during migration",
                    NbtAccess.string(upgradedPayload.get("extension"), ""));
        }
    }

    /**
     * A factory failure quarantines its whole object without preventing independent objects from
     * loading.
     */
    @Test
    void archive_factoryFailure_preservesRawObjectWithoutPartialActivation() {
        boolean[] failing = {false};
        var counter = type(Counter.class, Counter::new);
        var plain =
                type(
                        Plain.class,
                        () -> {
                            if (failing[0])
                                throw new IllegalStateException("Unavailable implementation");
                            return new Plain();
                        });
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            var rejected = source.create();
            rejected.attach(new Counter(), counter);
            rejected.attach(new Plain(), plain);
            var accepted = source.create();
            accepted.attach(new Counter(), counter);
            CompoundTag saved = source.writeNbt();
            CompoundTag expected = object(saved, 0).copy();
            failing[0] = true;
            assertEquals(1, restored.readNbt(saved).size());
            assertEquals(
                    List.of(accepted.id()),
                    restored.snapshot().stream().map(EcsObject::id).toList());
            assertEquals(expected, object(restored.writeNbt(), 0));
            assertTrue(restored.unresolvedObjectIds().contains(rejected.id()));
        }
    }

    /**
     * Stateless registered components need only a factory and restore without persistent-state
     * methods.
     */
    @Test
    void archive_plainTypedComponent_restoresUsingFactory() {
        var plain = type(Plain.class, Plain::new);
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            var object = source.create();
            object.attach(new Plain(), plain);
            assertTrue(restored.readNbt(source.writeNbt()).isEmpty());
            assertTrue(restored.get(object.id()).orElseThrow().has(plain));
        }
    }

    /**
     * Attachment failures produce diagnostics after all restored components receive their
     * callbacks.
     */
    @Test
    void archive_attachmentFailure_continuesOtherCallbacksAndKeepsLoadedState() {
        boolean[] failing = {false};
        List<UUID> callbacks = new ArrayList<>();
        Consumer<Probe> inspect =
                probe -> {
                    if (failing[0]) {
                        callbacks.add(probe.owner().orElseThrow().id());
                        throw new IllegalStateException("Fixture callback failure");
                    }
                };
        var type = type(Probe.class, () -> new Probe(inspect));
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            source.create().attach(new Probe(inspect), type);
            source.create().attach(new Probe(inspect), type);
            CompoundTag saved = source.writeNbt();
            failing[0] = true;
            assertEquals(2, restored.readNbt(saved).size());
            assertEquals(source.snapshot().stream().map(EcsObject::id).toList(), callbacks);
            assertEquals(2, restored.size());
            assertDoesNotThrow(restored::writeNbt);
            assertDoesNotThrow(restored::tickAll);
        }
    }

    /**
     * Invalid root structure never partially changes an empty manager or rolls back a populated
     * manager.
     */
    @Test
    void archive_invalidRootAndUsedManager_rejectBeforeMutation() {
        try (var source = new EcsObjectManager();
                var restored = new EcsObjectManager()) {
            source.create();
            CompoundTag saved = source.writeNbt();
            ListTag objects = (ListTag) saved.get("objects");
            objects.add(object(saved, 0).copy());
            assertThrows(EcsPersistenceException.class, () -> restored.readNbt(saved));
            assertEquals(0, restored.size());
            assertEquals(0, restored.lastTick());
            CompoundTag future = source.writeNbt();
            future.putInt("format", 2);
            assertThrows(
                    EcsPersistenceException.FutureFormat.class,
                    () -> restored.readNbt(future));
            assertThrows(IllegalStateException.class, () -> source.readNbt(source.writeNbt()));
        }
    }

    /**
     * Durable untyped components fail explicitly; explicitly transient objects are omitted
     * entirely.
     */
    @Test
    void archive_untypedDurableObject_requiresRegisteredTypeOrTransientFlag() {
        try (var source = new EcsObjectManager()) {
            EcsObject object = source.create();
            object.attach(new Counter());
            assertThrows(EcsPersistenceException.class, source::writeNbt);
            object.setPersistent(false);
            assertEquals(0, ((ListTag) source.writeNbt().get("objects")).size());
        }
    }

    /**
     * Registers a unique fixture descriptor.
     *
     * @param component class used for typed lookup
     * @param factory new detached instances
     * @param <C> component type
     * @return registered descriptor
     */
    private <C extends Component> ComponentType<C> type(
            Class<C> component, Supplier<C> factory) {
        return ComponentRegistry.instance()
                .register(
                        new ComponentType<>(
                                id(),
                                component,
                                ComponentType.DuplicatePolicy.REJECT,
                                factory));
    }

    /**
     * @return unique ID registered for cleanup by this test instance
     */
    private String id() {
        String value = "test:" + UUID.randomUUID();
        registrations.add(value);
        return value;
    }

    /**
     * Reads one fixture object compound.
     *
     * @param root archive root
     * @param index object ordinal
     * @return mutable fixture record
     */
    private static CompoundTag object(CompoundTag root, int index) {
        return (CompoundTag) ((ListTag) root.get("objects")).get(index);
    }

    /**
     * Reads one fixture component compound.
     *
     * @param record object record
     * @param index component ordinal
     * @return mutable fixture record
     */
    private static CompoundTag component(CompoundTag record, int index) {
        return (CompoundTag) ((ListTag) record.get("components")).get(index);
    }

    /**
     * Stateful persistent ticking fixture.
     */
    private static class Counter extends NetworkComponent implements TickableComponent {
        /**
         * Persisted and synchronized counter.
         */
        final TrackedField<Integer> value = tracked("value", FieldCodecs.intValue(), 0);

        /**
         * Advances the counter.
         *
         * @param tick manager sequence
         */
        @Override
        public void onTick(long tick) {
            value.update(previous -> previous + 1);
        }
    }

    /**
     * Stateless fixture using only registered construction and the base ownership contract.
     */
    private static final class Plain extends Component {}

    /**
     * Observes staged attachment after a complete archive load.
     */
    private static final class Probe extends Counter {
        /**
         * Attachment observer supplied by the test.
         */
        private final Consumer<Probe> inspect;

        /**
         * Creates an attachment fixture.
         *
         * @param inspect observer
         */
        private Probe(Consumer<Probe> inspect) {
            this.inspect = inspect;
        }

        /**
         * Observes membership after state restoration.
         */
        @Override
        public void onAttached() {
            inspect.accept(this);
        }
    }

    /**
     * Demonstrates an explicit schema upgrade.
     */
    private static final class UpgradedCounter extends Counter {
        /**
         * @return current schema version, two
         */
        @Override
        public int dataVersion() {
            return 2;
        }

        /**
         * Adds the version-two offset to the version-one counter.
         *
         * @param savedVersion previous schema version
         * @param nbt owned payload copy
         * @return migrated payload
         */
        @Override
        public CompoundTag migrateNbt(int savedVersion, CompoundTag nbt) {
            assertEquals(1, savedVersion);
            nbt.remove("obsolete");
            nbt.putInt("value", NbtAccess.number((NumericTag) nbt.get("value")).intValue() + 100);
            return nbt;
        }
    }
}
