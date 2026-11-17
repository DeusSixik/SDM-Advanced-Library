package dev.sixik.sdmal.api.ecs.serialization;

import dev.sixik.sdmal.api.ecs.sync.FieldCodec;
import dev.sixik.sdmal.core.ecs.serialization.NetworkSerialization;

/**
 * Public registry for direct binary value adapters, independent of NBT serialization. Register
 * adapters before component discovery; component frames are handled by the component API.
 */
public final class EcsNetworkSerializers {
    /**
     * Prevents utility construction.
     */
    private EcsNetworkSerializers() {
    }

    /**
     * Registers a binary adapter for an exact non-generic type.
     *
     * @param type       concrete value class
     * @param serializer stateless binary adapter
     * @param <T>        value type
     * @throws IllegalStateException if the type was already registered or resolved
     */
    public static <T> void register(Class<T> type, EcsNetworkSerializer<T> serializer) {
        NetworkSerialization.register(type, serializer);
    }

    /**
     * Registers a binary adapter for an exact generic type.
     *
     * @param type       concrete generic type token
     * @param serializer stateless binary adapter
     * @param <T>        value type
     * @throws IllegalStateException if the type was already registered or resolved
     */
    public static <T> void register(TypeRef<T> type, EcsNetworkSerializer<T> serializer) {
        NetworkSerialization.register(type, serializer);
    }

    /**
     * Resolves a shared binary adapter for a non-generic value.
     *
     * @param type exact value class
     * @param <T>  value type
     * @return cached adapter, never null
     * @throws EcsSerializationException if the type has no supported finite schema
     */
    public static <T> EcsNetworkSerializer<T> serializer(Class<T> type) {
        return NetworkSerialization.serializer(type);
    }

    /**
     * Resolves a shared binary adapter for a generic value.
     *
     * @param type concrete generic type token
     * @param <T>  value type
     * @return cached adapter, never null
     * @throws EcsSerializationException if the type has no supported finite schema
     */
    public static <T> EcsNetworkSerializer<T> serializer(TypeRef<T> type) {
        return NetworkSerialization.serializer(type);
    }

    /**
     * Reuses an existing field codec's direct buffer operations.
     *
     * @param codec stateless codec for non-null values
     * @param <T>   value type
     * @return binary adapter with the codec's copy semantics
     */
    public static <T> EcsNetworkSerializer<T> fromCodec(FieldCodec<T> codec) {
        return NetworkSerialization.fromCodec(codec);
    }
}
