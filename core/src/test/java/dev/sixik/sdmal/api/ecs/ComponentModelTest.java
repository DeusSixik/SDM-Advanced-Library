package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.api.ecs.sync.NetworkComponent;
import dev.sixik.sdmal.api.ecs.sync.TrackedField;
import dev.sixik.sdmal.example.ecs.shop.CatalogComponent;
import dev.sixik.sdmal.example.ecs.shop.ShopEcsExample;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;
import io.netty.buffer.Unpooled;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies the two-base API against standalone application data, legacy saves and tracked snapshots. */
class ComponentModelTest {
    /** Makes the shipped example available as it is after normal mod setup. */
    @BeforeAll static void discover() {
        ComponentRegistry.instance().registerAnnotated(CatalogComponent.class);
    }

    /** Shop data can be created, queried, saved and restored without a Level or any tick. */
    @Test void shop_withoutWorldOrTicks_roundTripsAutomaticallyTypedComponents() {
        try (var manager = new EcsObjectManager(); var restored = new EcsObjectManager()) {
            var shop = ShopEcsExample.createShop(manager, "building", 4);
            assertTrue(shop.manager().isPresent());
            assertEquals(0, manager.lastTick());
            assertEquals("sdmal_example:shop_catalog", shop.entries().get(0).type().id());
            assertThrows(DuplicateComponentException.class, () -> shop.attach(new CatalogComponent()));
            var saved = manager.writeNbt();
            assertTrue(restored.readNbt(saved).isEmpty());
            var catalog = restored.get(shop.id()).orElseThrow().getSingle(CatalogComponent.class).orElseThrow();
            assertEquals("building", catalog.catalogId()); assertEquals(4, catalog.order());
            assertThrows(IllegalStateException.class, () -> restored.context().level());
        }
    }

    /** Annotation-based data components exchange full snapshots through the same default base. */
    @Test void component_binarySnapshot_preservesSelectedCatalogData() {
        var source = new CatalogComponent("machines", 3); var target = new CatalogComponent();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            source.writeNetwork(buffer); target.readNetwork(buffer);
            assertEquals("machines", target.catalogId()); assertEquals(3, target.order());
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }

    /** A data-only component has the same owner-thread mutation discipline as a ticking component. */
    @Test void component_foreignMutation_rejectsWithoutChangingShopData() throws Exception {
        try (var manager = new EcsObjectManager()) {
            var shop = ShopEcsExample.createShop(manager, "original", 0);
            var catalog = shop.getSingle(CatalogComponent.class).orElseThrow();
            var worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> assertThrows(IllegalStateException.class, () -> catalog.rename("wrong")))
                        .get(3, TimeUnit.SECONDS);
                manager.execute(() -> catalog.rename("queued")); manager.tickAll();
                assertEquals("queued", catalog.catalogId());
            } finally { worker.shutdownNow(); }
        }
    }

    /** Names alone do not require a new archive format or discard old version-zero stateless records. */
    @Test void archive_legacyStatelessComponent_preservesIdentityAndUnknownPayload() {
        String id = "component_model:stateless";
        var type = ComponentRegistry.instance().register(new ComponentType<>(id, Stateless.class,
                ComponentType.DuplicatePolicy.REJECT, Stateless::new));
        try (var manager = new EcsObjectManager(); var restored = new EcsObjectManager()) {
            var object = manager.create(); object.attach(new Stateless(), type);
            var saved = manager.writeNbt();
            var objectTag = (CompoundTag) ((ListTag) saved.get("objects")).get(0);
            var componentTag = (CompoundTag) ((ListTag) objectTag.get("components")).get(0);
            componentTag.putInt("version", 0);
            ((CompoundTag) componentTag.get("data")).putString("addon", "keep");
            assertTrue(restored.readNbt(saved).isEmpty());
            assertTrue(restored.get(object.id()).orElseThrow().has(Stateless.class));
            var rewritten = restored.writeNbt();
            var newObject = (CompoundTag) ((ListTag) rewritten.get("objects")).get(0);
            var newComponent = (CompoundTag) ((ListTag) newObject.get("components")).get(0);
            assertEquals(1, NbtAccess.number((NumericTag) newComponent.get("version")).intValue());
            assertEquals("keep", NbtAccess.string(((CompoundTag) newComponent.get("data")).get("addon"), ""));
        } finally { ComponentRegistry.instance().unregister(id); }
    }

    /** NetworkComponent is a Component and its generic snapshot methods use tracked wire data. */
    @Test void networkBehaviour_fullThenDelta_retainsExistingRevisionProtocol() {
        var source = new NetworkCounter(); var target = new NetworkCounter();
        Component genericSource = source; Component genericTarget = target;
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            source.count.set(7); genericSource.writeNetwork(buffer); genericTarget.readNetwork(buffer);
            assertEquals(7, target.count.get()); assertFalse(buffer.isReadable());
            assertEquals(source.schemaFingerprint(), genericSource.networkSchemaFingerprint());
            buffer.clear();
            var sent = new NetworkComponent.DeltaTracker(); var received = new NetworkComponent.DeltaTracker();
            source.count.set(8); assertTrue(source.writeDelta(buffer, sent)); target.readDelta(buffer, received);
            assertEquals(8, target.count.get());
            buffer.clear(); assertFalse(source.writeDelta(buffer, sent));
        } finally { buffer.release(); }
    }

    /** Public field metadata cannot be cast to a raw view or overwritten through toArray(). */
    @Test void networkComponent_fieldSchema_hidesBackingArrayAndRemainsStable() {
        var source = new NetworkCounter();
        var schema = source.syncedFields();
        assertSame(schema, source.syncedFields());
        assertFalse(schema instanceof dev.sixik.sdmal.core.utils.ObjectArrayView<?>);
        Object[] copied = schema.toArray(); copied[0] = null;
        assertSame(source.count, source.syncedFields().get(0));
        assertThrows(UnsupportedOperationException.class, schema::clear);
    }

    /** Automatically annotated classes cannot silently become untyped if discovery was omitted. */
    @Test void attach_undiscoveredAnnotatedComponent_rejectsBeforePublishing() {
        var object = new EcsObject();
        assertThrows(IllegalStateException.class, () -> object.attach(new Undiscovered()));
        assertTrue(object.components().isEmpty());
    }

    /** Specialized network data must not silently ignore ordinary annotated state. */
    @Test void networkBehaviour_annotatedField_rejectsAmbiguousSerializationModel() {
        assertThrows(IllegalArgumentException.class, () -> ComponentRegistry.instance().register(
                new ComponentType<>("component_model:mixed", MixedNetwork.class,
                        ComponentType.DuplicatePolicy.REJECT, MixedNetwork::new)));
        assertTrue(ComponentRegistry.instance().get("component_model:mixed").isEmpty());
    }

    /** Stateless version-zero fixture resembling the former ownership-only base. */
    private static final class Stateless extends Component {}
    /** Explicit tracked state fixture; no extra base or persistent interface is required. */
    private static final class NetworkCounter extends NetworkComponent {
        /** Persisted and synchronized counter, initially zero. */
        final TrackedField<Integer> count = tracked("count", FieldCodecs.intValue(), 0);
    }
    /** Unindexed test type with a valid annotation, deliberately excluded from service discovery. */
    @EcsComponent(id = "component_model:undiscovered")
    private static final class Undiscovered extends Component {}
    /** Unsupported mixed model fixture; callers choose plain Component or explicit tracked handles. */
    private static final class MixedNetwork extends NetworkComponent {
        /** Annotation-selected state would be ignored by the explicit-field serializer. */
        @dev.sixik.sdmal.api.ecs.annotations.EcsPersist("value")
        private int value;
    }
}
