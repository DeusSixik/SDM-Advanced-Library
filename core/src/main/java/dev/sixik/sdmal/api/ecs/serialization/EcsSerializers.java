package dev.sixik.sdmal.api.ecs.serialization;

import dev.sixik.sdmal.api.ecs.sync.FieldCodec;
import dev.sixik.sdmal.core.ecs.serialization.NbtSerialization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Public entry point for custom NBT adapters and standalone annotated values. Register adapters
 * during mod initialization, before component discovery freezes their schemas.
 */
public final class EcsSerializers {
    /**
     * Prevents utility construction.
     */
    private EcsSerializers() {
    }

    /**
     * Registers an adapter for an exact non-generic type.
     *
     * @param type       concrete value class
     * @param serializer stateless adapter, non-null
     * @param <T>        value type
     * @throws IllegalStateException if the type was already registered or resolved
     */
    public static <T> void register(Class<T> type, EcsSerializer<T> serializer) {
        NbtSerialization.register(type, serializer);
    }

    /**
     * Registers an adapter for an exact generic type.
     *
     * @param type       concrete generic type token
     * @param serializer stateless adapter, non-null
     * @param <T>        value type
     * @throws IllegalStateException if the type was already registered or resolved
     */
    public static <T> void register(TypeRef<T> type, EcsSerializer<T> serializer) {
        NbtSerialization.register(type, serializer);
    }

    /**
     * Resolves a shared adapter for a non-generic value.
     *
     * @param type exact value class
     * @param <T>  value type
     * @return cached adapter, never null
     * @throws EcsSerializationException if the type has no supported finite schema
     */
    public static <T> EcsSerializer<T> serializer(Class<T> type) {
        return NbtSerialization.serializer(type);
    }

    /**
     * Resolves a shared adapter for a generic value.
     *
     * @param type concrete generic type token
     * @param <T>  value type
     * @return cached adapter, never null
     * @throws EcsSerializationException if the type has no supported finite schema
     */
    public static <T> EcsSerializer<T> serializer(TypeRef<T> type) {
        return NbtSerialization.serializer(type);
    }

    /**
     * Encodes a complete annotated field envelope for migrations and tools.
     *
     * @param type  exact value class; primitive tokens reject null
     * @param value value, possibly null for a reference type
     * @param <T>   value type
     * @return independently owned nullable field envelope
     */
    public static <T> CompoundTag writeValue(Class<T> type, T value) {
        return NbtSerialization.writeValue(type, value);
    }

    /**
     * Encodes a complete generic field envelope for migrations and tools.
     *
     * @param type  concrete generic type token
     * @param value value, possibly null
     * @param <T>   value type
     * @return independently owned nullable field envelope
     */
    public static <T> CompoundTag writeValue(TypeRef<T> type, T value) {
        return NbtSerialization.writeValue(type, value);
    }

    /**
     * Decodes a complete annotated field envelope without modifying its source.
     *
     * @param type exact value class; primitive tokens reject stored null
     * @param tag  field envelope
     * @param <T>  value type
     * @return decoded value, possibly an explicitly stored null
     */
    public static <T> T readValue(Class<T> type, Tag tag) {
        return NbtSerialization.readValue(type, tag);
    }

    /**
     * Decodes a complete generic field envelope without modifying its source.
     *
     * @param type concrete generic type token
     * @param tag  field envelope
     * @param <T>  value type
     * @return decoded value, possibly an explicitly stored null
     */
    public static <T> T readValue(TypeRef<T> type, Tag tag) {
        return NbtSerialization.readValue(type, tag);
    }

    /**
     * Reuses an existing field codec as an NBT adapter.
     *
     * @param codec stateless codec for non-null values
     * @param <T>   value type
     * @return NBT adapter with the codec's copy semantics
     */
    public static <T> EcsSerializer<T> fromCodec(FieldCodec<T> codec) {
        return NbtSerialization.fromCodec(codec);
    }
}
