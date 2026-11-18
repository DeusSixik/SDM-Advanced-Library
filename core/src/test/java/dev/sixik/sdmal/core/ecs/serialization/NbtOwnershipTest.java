package dev.sixik.sdmal.core.ecs.serialization;

import static org.junit.jupiter.api.Assertions.*;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializers;
import dev.sixik.sdmal.api.ecs.serialization.TypeRef;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Ensures trusted structural adapters retain defensive boundaries around nested consumer adapters.
 */
class NbtOwnershipTest {
    /**
     * Runtime validation preserves trusted ownership only for the library's own structural adapters.
     */
    @Test
    void schemaValidation_preservesBuiltinOwnershipWithoutTrustingCustomAdapters() {
        var type = new TypeRef<List<Integer>>() {};
        assertInstanceOf(OwnedNbtSerializer.class, EcsSerializers.serializer(type));
        assertFalse(EcsSerializers.serializer(MutableValue.class) instanceof OwnedNbtSerializer<?>);
    }

    /**
     * Registers a deliberately stateful consumer format before any container schema is resolved.
     */
    @BeforeAll
    static void registerAdapters() {
        EcsSerializers.register(
                MutableValue.class,
                new EcsSerializer<>() {
                    /**
                     * @param value shared consumer value
                     * @return deliberately shared payload
                     */
                    @Override
                    public Tag write(MutableValue value) {
                        return value.tag;
                    }

                    /**
                     * @param tag exclusively owned payload
                     * @return value retaining and modifying its own copy
                     */
                    @Override
                    public MutableValue read(Tag tag) {
                        CompoundTag owned = (CompoundTag) tag;
                        owned.putBoolean("read", true);
                        return new MutableValue(owned);
                    }
                });
    }

    /**
     * A nested custom adapter may return shared tags and consume its input without aliasing a saved
     * archive.
     */
    @Test
    void nestedCustomAdapter_sharedOutputAndDestructiveRead_keepArchiveIndependent() {
        var source = new Container();
        CompoundTag shared = new CompoundTag();
        shared.putInt("number", 1);
        source.values = List.of(new MutableValue(shared));
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        CompoundTag original = saved.copy();
        shared.putInt("number", 2);
        assertEquals(original, saved);
        var target = new Container();
        target.readNbt(saved);
        assertEquals(original, saved);
        assertNotSame(shared, target.values.get(0).tag);
        target.values.get(0).tag.putInt("number", 3);
        assertEquals(original, saved);
    }

    /**
     * An arbitrary FieldCodec remains untrusted when adapted through the public API.
     */
    @Test
    void publicCodecAdapter_sharedTag_keepsDefensiveCopies() {
        var codec =
                dev.sixik.sdmal.api.ecs.sync.FieldCodec.<CompoundTag>builder()
                        .schema("test:shared")
                        .nbt(
                                (tag, key, value) -> tag.put(key, value),
                                (tag, key, fallback) -> (CompoundTag) tag.get(key))
                        .nbtElement(value -> value, (tag, fallback) -> (CompoundTag) tag)
                        .network((wire, value) -> {}, wire -> new CompoundTag())
                        .build();
        EcsSerializer<CompoundTag> adapter = EcsSerializers.fromCodec(codec);
        CompoundTag source = new CompoundTag();
        source.putInt("n", 1);
        CompoundTag envelope = EcsValues.write(adapter, source);
        CompoundTag encoded = envelope.copy();
        source.putInt("n", 2);
        assertEquals(encoded, envelope);
        CompoundTag decoded = (CompoundTag) EcsValues.read(adapter, envelope);
        decoded.putInt("n", 3);
        assertEquals(encoded, envelope);
    }

    /**
     * Explicit null differs from an absent key after presence-array removal.
     */
    @Test
    void component_missingAndNullKeys_retainDefaultsOrInstallNull() {
        var source = new Container();
        source.values = null;
        CompoundTag saved = new CompoundTag();
        source.writeNbt(saved);
        var target = new Container();
        target.readNbt(saved);
        assertNull(target.values);
        target.values = List.of();
        saved.remove("values");
        target.readNbt(saved);
        assertEquals(List.of(), target.values);
    }

    /**
     * Consumer payload whose NBT identity must never cross an adapter boundary.
     *
     * @param tag mutable consumer-owned payload
     */
    private record MutableValue(CompoundTag tag) {}

    /**
     * Structural list adapter wrapping a custom element adapter.
     */
    private static final class Container extends Component {
        /**
         * Nullable nested values; default is an empty immutable list.
         */
        @EcsPersist("values")
        private List<MutableValue> values = List.of();
    }
}
