package dev.sixik.sdmal.api.ecs;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.codecs.ExternFieldCodecs;
import dev.sixik.sdmal.api.ecs.sync.Field;
import dev.sixik.sdmal.api.ecs.sync.FieldCodec;
import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.api.ecs.sync.SyncException;
import dev.sixik.sdmal.api.ecs.sync.SyncedWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.sync.SyncedWorldObjectComponent.DeltaTracker;
import dev.sixik.sdmal.api.ecs.sync.TrackedField;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import io.netty.buffer.Unpooled;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests the port against each version's real Minecraft NBT and network classes.
 */
class ComponentSerializationTest {
    /**
     * Initializes Minecraft types before network and NBT tests.
     */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Scalar codecs must preserve values and defaults on NBT and wire round-trips.
     */
    @Test
    void codecs_scalarValues_roundTripNbtAndNetwork() {
        roundTrip(FieldCodecs.bool(), true, false);
        roundTrip(FieldCodecs.byteValue(), (byte) -128, (byte) 42);
        roundTrip(FieldCodecs.intValue(), Integer.MIN_VALUE, 42);
        roundTrip(FieldCodecs.longValue(), Long.MAX_VALUE, 42L);
        roundTrip(FieldCodecs.floatValue(), 1.25f, -3.5f);
        roundTrip(FieldCodecs.doubleValue(), -123.5, 42.0);
        roundTrip(FieldCodecs.string(), "текст 世界", "fallback");
        roundTrip(
                FieldCodecs.uuid(),
                UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210"),
                new UUID(0, 0));
    }

    /**
     * Position codecs preserve negative coordinates and return defaults for absent or malformed
     * tags.
     */
    @Test
    void codecs_minecraftPositions_preserveCoordinatesAndDefaults() {
        roundTrip(ExternFieldCodecs.blockPos(), new BlockPos(-12, -23, 456), BlockPos.ZERO);
        roundTrip(ExternFieldCodecs.chunkPos(), new ChunkPos(-42, 357), new ChunkPos(0, 0));
        FieldCodec<SectionPos> codec = ExternFieldCodecs.sectionPos();
        SectionPos expected = SectionPos.of(-12, 34, -56);
        CompoundTag tag = new CompoundTag();
        codec.toNbt(tag, "position", expected);
        assertEquals(
                expected.asLong(), codec.fromNbt(tag, "position", SectionPos.of(0, 0, 0)).asLong());
        FriendlyByteBuf wire = buffer();
        try {
            codec.toNetwork(wire, expected);
            assertEquals(expected.asLong(), codec.fromNetwork(wire).asLong());
        } finally {
            wire.release();
        }
    }

    /**
     * Malformed identifier tags retain the configured fallback instead of producing null.
     */
    @Test
    void codec_identifier_invalidNbtKeepsFallback() {
        var codec = ExternFieldCodecs.resourceLocation();
        var fallback = dev.sixik.sdmal.SDMAdvancedLibrary.id("fallback");
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "INVALID ID");
        assertEquals(fallback, codec.fromNbt(nbt, "id", fallback));
        roundTrip(codec, dev.sixik.sdmal.SDMAdvancedLibrary.id("example"), fallback);
    }

    /**
     * Rewriting an optional field to empty must overwrite the previously persisted value.
     */
    @Test
    void codec_optionalEmpty_overwritesExistingSaveAndReadsLegacyValue() {
        var codec = FieldCodecs.optionalOf(FieldCodecs.intValue());
        CompoundTag nbt = new CompoundTag();
        codec.toNbt(nbt, "field", Optional.of(8));
        assertEquals(Optional.of(8), codec.fromNbt(nbt, "field", Optional.empty()));
        codec.toNbt(nbt, "field", Optional.empty());
        assertEquals(Optional.empty(), codec.fromNbt(nbt, "field", Optional.of(99)));
        nbt.putInt("field", 12);
        assertEquals(Optional.of(12), codec.fromNbt(nbt, "field", Optional.empty()));
        var compoundOptional = FieldCodecs.optionalOf(FieldCodecs.compound());
        compoundOptional.toNbt(nbt, "field", Optional.of(new CompoundTag()));
        assertTrue(compoundOptional.fromNbt(nbt, "field", Optional.empty()).isPresent());
    }

    /**
     * Lists and maps must recursively copy mutable child values.
     */
    @Test
    void codecs_collectionsAndCompounds_copyWithoutAliasing() {
        CompoundTag child = new CompoundTag();
        child.putInt("value", 7);
        var codec = FieldCodecs.listOf(FieldCodecs.compound());
        var values = new ObjectArrayList<CompoundTag>();
        values.add(child);
        Field<it.unimi.dsi.fastutil.objects.ObjectList<CompoundTag>> field =
                new Field<>("list", codec, values);
        child.putInt("value", 8);
        assertEquals(
                7,
                NbtAccess.number((net.minecraft.nbt.NumericTag) field.get().get(0).get("value"))
                        .intValue());
        var map = new Object2ObjectOpenHashMap<String, Integer>();
        map.put("a", 17);
        roundTrip(
                FieldCodecs.mapOf(FieldCodecs.string(), FieldCodecs.intValue()),
                map,
                new Object2ObjectOpenHashMap<>());
        roundTrip(codec, values, new ObjectArrayList<>());
        assertEquals("null", new Field<>("nullable", FieldCodecs.string(), null).toString());
    }

    /**
     * Field declarations become immutable when serialization first observes their schema.
     */
    @Test
    void fields_lateDeclarationAndDuplicateName_failClearly() {
        Flexible component = new Flexible();
        component.add("first");
        assertThrows(IllegalArgumentException.class, () -> component.add("first"));
        assertEquals(1, component.syncedFields().size());
        assertThrows(IllegalStateException.class, () -> component.add("second"));
    }

    /**
     * Frozen field schemas are reused and fingerprint construction evaluates stable codec names
     * once.
     */
    @Test
    void schema_repeatedAccess_reusesImmutableMetadata() {
        Example component = new Example();
        assertSame(component.syncedFields(), component.syncedFields());
        assertThrows(UnsupportedOperationException.class, () -> component.syncedFields().clear());
        long expected = component.schemaFingerprint();
        assertEquals(expected, component.schemaFingerprint());
        component.count.set(42);
        assertEquals(expected, component.schemaFingerprint());
        assertEquals(expected, new Example().schemaFingerprint());
    }

    /**
     * NBT is name-based, tolerates unknown keys and keeps current values for missing keys.
     */
    @Test
    void component_nbtRoundTripAndPartialLoad_preservesFields() {
        Example source = new Example();
        source.count.set(37);
        source.local.set("saved");
        CompoundTag nbt = new CompoundTag();
        source.writeNbt(nbt);
        Example restored = new Example();
        restored.readNbt(nbt);
        assertEquals(37, restored.count.get());
        assertEquals("saved", restored.local.get());
        CompoundTag partial = new CompoundTag();
        partial.putString("extra", "unknown");
        restored.readNbt(partial);
        assertEquals(37, restored.count.get());
    }

    /**
     * Full sync excludes plain fields and deltas ignore stale re-delivery.
     */
    @Test
    void component_fullThenOutOfOrderDeltas_preservesNewestValues() {
        Example server = new Example();
        Example client = new Example();
        DeltaTracker sender = new DeltaTracker();
        DeltaTracker receiver = new DeltaTracker();
        FriendlyByteBuf full = buffer();
        FriendlyByteBuf old = buffer();
        FriendlyByteBuf latest = buffer();
        FriendlyByteBuf unchanged = buffer();
        try {
            server.local.set("private");
            server.writeFull(full);
            long[] revisions = client.readFullWithRevisions(full);
            sender.reset(revisions);
            receiver.reset(revisions);
            assertEquals("local", client.local.get());
            server.count.set(1);
            assertTrue(server.writeDelta(old, sender));
            server.count.set(2);
            assertTrue(server.writeDelta(latest, sender));
            client.readDelta(latest, receiver);
            client.readDelta(old, receiver);
            assertEquals(2, client.count.get());
            assertFalse(server.writeDelta(unchanged, sender));
            assertEquals(0, unchanged.writerIndex());
        } finally {
            full.release();
            old.release();
            latest.release();
            unchanged.release();
        }
    }

    /**
     * A field updated while a codec writes must remain pending for the next delta.
     */
    @Test
    void component_changeDuringEncoding_doesNotAdvancePastEncodedRevision() {
        Racing component = new Racing();
        component.count.set(10);
        DeltaTracker tracker = new DeltaTracker();
        FriendlyByteBuf first = buffer();
        FriendlyByteBuf second = buffer();
        try {
            component.writeDelta(first, tracker);
            assertEquals(20, component.count.get());
            assertEquals(1, tracker.known(0));
            assertEquals(1, first.readVarInt());
            assertEquals(0, first.readVarInt());
            assertEquals(1, first.readVarLong());
            assertEquals(10, first.readVarInt());
            assertTrue(component.writeDelta(second, tracker));
            assertEquals(2, tracker.known(0));
        } finally {
            first.release();
            second.release();
        }
    }

    /**
     * Invalid or incomplete packets are decoded completely before any field is changed.
     */
    @Test
    void component_truncatedFullAndInvalidDelta_doNotPartiallyApply() {
        Pair client = new Pair();
        FriendlyByteBuf full = buffer();
        FriendlyByteBuf delta = buffer();
        try {
            full.writeVarInt(2);
            full.writeVarLong(1);
            full.writeVarLong(1);
            full.writeVarInt(99);
            assertThrows(IndexOutOfBoundsException.class, () -> client.readFull(full));
            assertEquals(0, client.a.get());
            DeltaTracker tracker = new DeltaTracker();
            delta.writeVarInt(2);
            delta.writeVarInt(0);
            delta.writeVarLong(1);
            delta.writeVarInt(99);
            delta.writeVarInt(0);
            delta.writeVarLong(2);
            delta.writeVarInt(100);
            assertThrows(SyncException.class, () -> client.readDelta(delta, tracker));
            assertEquals(0, client.a.get());
            assertEquals(-1, tracker.known(0));
        } finally {
            full.release();
            delta.release();
        }
    }

    /**
     * Invalid field counts must be rejected before allocating revision arrays.
     */
    @Test
    void component_wrongFieldCount_rejectsPacket() {
        FriendlyByteBuf wire = buffer();
        try {
            wire.writeVarInt(Integer.MAX_VALUE);
            assertThrows(SyncException.class, () -> new Example().readFull(wire));
        } finally {
            wire.release();
        }
    }

    /**
     * Tracker reset copies the sender array, shrinks stale slots and rejects invalid revisions.
     */
    @Test
    void tracker_reset_copiesAndReplacesAllSlots() {
        DeltaTracker tracker = new DeltaTracker(4);
        tracker.mark(3, 99);
        long[] revisions = {1, 2};
        tracker.reset(revisions);
        revisions[0] = 999;
        assertEquals(1, tracker.known(0));
        assertEquals(-1, tracker.known(3));
        assertThrows(IllegalArgumentException.class, () -> tracker.mark(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> tracker.reset(new long[] {-1}));
    }

    /**
     * Atomic read-modify-write must not lose increments from concurrent writers.
     *
     * @throws Exception if worker execution fails or times out
     */
    @Test
    void trackedField_concurrentUpdates_preservesValuesAndRevisions() throws Exception {
        TrackedField<Integer> field = new TrackedField<>("counter", FieldCodecs.intValue(), 0);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first =
                    executor.submit(
                            () -> {
                                for (int i = 0; i < 1000; i++) field.update(value -> value + 1);
                            });
            var second =
                    executor.submit(
                            () -> {
                                for (int i = 0; i < 1000; i++) field.update(value -> value + 1);
                            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(2000, field.get());
            assertEquals(2000, field.revision());
            assertFalse(field.set(2000));
            assertEquals(new TrackedField.Snapshot<>(2000, 2000), field.snapshot());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Single-threaded persistence rejects a foreign thread after first access.
     *
     * @throws Exception if worker execution fails or times out
     */
    @Test
    void singleThreadedComponent_foreignNbtAccess_rejectsWrongThread() throws Exception {
        Single component = new Single();
        component.writeNbt(new CompoundTag());
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(
                            () ->
                                    assertThrows(
                                            IllegalStateException.class,
                                            () -> component.readNbt(new CompoundTag())))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Reconfiguring a builder must not mutate functions already captured by a built codec.
     */
    @Test
    void codecBuilder_reusedAfterBuild_preservesExistingCodec() {
        var builder =
                FieldCodec.<Integer>builder()
                        .schema("test:int")
                        .nbt(
                                (nbt, key, value) -> nbt.putInt(key, value),
                                (nbt, key, fallback) ->
                                        NbtAccess.number(
                                                        (net.minecraft.nbt.NumericTag) nbt.get(key))
                                                .intValue())
                        .network(
                                (buf, value) -> buf.writeVarInt(value),
                                FriendlyByteBuf::readVarInt);
        FieldCodec<Integer> first = builder.build();
        builder.network((buf, value) -> buf.writeVarInt(999), FriendlyByteBuf::readVarInt);
        FriendlyByteBuf wire = buffer();
        try {
            first.toNetwork(wire, 42);
            assertEquals(42, first.fromNetwork(wire));
        } finally {
            wire.release();
        }
    }

    /**
     * Verifies both persistence and network representations through an independent destination.
     *
     * @param codec codec being tested
     * @param value round-trip value
     * @param fallback value expected for missing or malformed NBT
     * @param <T> codec value type
     */
    private static <T> void roundTrip(FieldCodec<T> codec, T value, T fallback) {
        CompoundTag nbt = new CompoundTag();
        assertEquals(fallback, codec.fromNbt(nbt, "field", fallback));
        codec.toNbt(nbt, "field", value);
        assertEquals(value, codec.fromNbt(nbt, "field", fallback));
        FriendlyByteBuf wire = buffer();
        try {
            codec.toNetwork(wire, value);
            assertEquals(value, codec.fromNetwork(wire));
            assertEquals(0, wire.readableBytes());
        } finally {
            wire.release();
        }
    }

    /**
     * Allocates a network fixture whose reference must be released by the caller.
     *
     * @return empty reference-counted buffer
     */
    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    /**
     * Component containing both synchronized and NBT-only fields.
     */
    private static class Example extends SyncedWorldObjectComponent {
        /**
         * Networked count, initially zero.
         */
        final TrackedField<Integer> count = tracked("count", FieldCodecs.intValue(), 0);

        /**
         * NBT-only string excluded from the network schema.
         */
        final Field<String> local = field("local", FieldCodecs.string(), "local");
    }

    /**
     * Two-field component used to verify all packet bytes decode before values are applied.
     */
    private static class Pair extends SyncedWorldObjectComponent {
        /**
         * First synchronized value, initially zero.
         */
        final TrackedField<Integer> a = tracked("a", FieldCodecs.intValue(), 0);

        /**
         * Second synchronized value, initially zero.
         */
        final TrackedField<Integer> b = tracked("b", FieldCodecs.intValue(), 0);
    }

    /**
     * Fixture exposing declaration checks.
     */
    private static class Flexible extends FieldedWorldObjectComponent {
        /**
         * Adds a constructor-style field.
         *
         * @param name field name
         */
        void add(String name) {
            field(name, FieldCodecs.intValue(), 0);
        }
    }

    /**
     * Single-thread persistence fixture with one plain field.
     */
    private static class Single extends SingleThreadedWorldObjectComponent {
        /**
         * NBT-only counter.
         */
        final Field<Integer> count = field("count", FieldCodecs.intValue(), 0);
    }

    /**
     * Forces a field change during encoding to reproduce the source's lost-delta race.
     */
    private static class Racing extends SyncedWorldObjectComponent {
        /**
         * Limits the mutation during encoding to the first packet.
         */
        final AtomicBoolean mutate = new AtomicBoolean(true);

        /**
         * Field whose codec invokes the mutation while serializing its captured old value.
         */
        final TrackedField<Integer> count =
                tracked(
                        "count",
                        FieldCodec.<Integer>builder()
                                .schema("test:racing")
                                .nbt(
                                        (nbt, key, value) -> nbt.putInt(key, value),
                                        (nbt, key, fallback) -> fallback)
                                .network(
                                        (buf, value) -> {
                                            if (mutate.getAndSet(false)) changeDuringWrite();
                                            buf.writeVarInt(value);
                                        },
                                        FriendlyByteBuf::readVarInt)
                                .build(),
                        0);

        /**
         * Changes live state after the writer has captured its snapshot.
         */
        void changeDuringWrite() {
            count.set(20);
        }
    }
}
