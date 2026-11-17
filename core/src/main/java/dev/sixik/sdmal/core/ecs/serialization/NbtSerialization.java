package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;
import dev.sixik.sdmal.api.ecs.serialization.TypeRef;
import dev.sixik.sdmal.api.ecs.sync.FieldCodec;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process-wide, exact-type registry and cached annotated schemas. Register adapters before
 * component types. Successful schemas freeze their resolved dependencies; replacing built-in,
 * registered or already resolved adapters is rejected. This prevents two live components from
 * interpreting the same type differently. Schema discovery runs only at registration/first use;
 * ordinary world ticks are unaffected.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class NbtSerialization {
    /**
     * Built-in and explicitly registered adapters, guarded by this class's monitor.
     */
    private static final Map<Type, EcsSerializer<?>> REGISTERED = EcsBuiltinSerializers.create();

    /**
     * Successfully resolved structural adapters, guarded by this class's monitor.
     */
    private static final Map<Type, EcsSerializer<?>> RESOLVED = new Object2ObjectOpenHashMap<>();

    /**
     * Immutable component plans, guarded by this class's monitor.
     */
    private static final Map<Class<?>, EcsComponentSchema> COMPONENTS = new Object2ObjectOpenHashMap<>();

    /**
     * Per-member adapter instances, shared once per implementation class.
     */
    private static final Map<Class<? extends EcsSerializer<?>>, EcsSerializer<?>> OVERRIDES =
            new Object2ObjectOpenHashMap<>();

    /**
     * Prevents utility construction.
     */
    private NbtSerialization() {
    }

    /**
     * Validates a public type token without exposing internal reflection helpers.
     *
     * @param type captured concrete type
     * @throws IllegalArgumentException if the type contains unresolved arguments
     */
    public static void validateValueType(Type type) {
        EcsTypes.requireConcrete(type);
    }

    /**
     * Registers an adapter for one exact non-generic class.
     *
     * @param type       concrete class, never an erased generic class
     * @param serializer stateless NBT-only adapter
     * @param <T>        value type
     * @throws IllegalStateException    if this type is already registered or resolved
     * @throws IllegalArgumentException if type is not concrete
     */
    public static synchronized <T> void register(Class<T> type, EcsSerializer<T> serializer) {
        registerType(type, serializer);
    }

    /**
     * Registers an adapter for one exact generic type without conflating other element types.
     *
     * @param type       captured concrete type
     * @param serializer stateless NBT-only adapter
     * @param <T>        value type
     * @throws IllegalStateException if this exact type is already registered or resolved
     */
    public static synchronized <T> void register(TypeRef<T> type, EcsSerializer<T> serializer) {
        registerType(Objects.requireNonNull(type, "type").type(), serializer);
    }

    /**
     * Applies registry invariants before installing an explicit adapter.
     *
     * @param type       exact type
     * @param serializer adapter, non-null
     */
    private static void registerType(Type type, EcsSerializer<?> serializer) {
        EcsTypes.requireConcrete(Objects.requireNonNull(type, "type"));
        type = EcsTypes.boxed(type);
        Objects.requireNonNull(serializer, "serializer");
        if (REGISTERED.containsKey(type) || RESOLVED.containsKey(type))
            throw new IllegalStateException(
                    "ECS serializer already registered or resolved: " + type.getTypeName());
        REGISTERED.put(type, serializer);
    }

    /**
     * Resolves and caches a non-generic value adapter.
     *
     * @param type exact class
     * @param <T>  value type
     * @return shared non-null value adapter; null envelopes are owned by annotated fields
     * @throws EcsSerializationException if no safe finite schema can be built
     */
    public static synchronized <T> EcsSerializer<T> serializer(Class<T> type) {
        return resolve(type);
    }

    /**
     * Resolves and caches a generic value adapter.
     *
     * @param type concrete generic type token
     * @param <T>  value type
     * @return shared non-null value adapter
     * @throws EcsSerializationException if no safe finite schema can be built
     */
    public static synchronized <T> EcsSerializer<T> serializer(TypeRef<T> type) {
        return resolve(Objects.requireNonNull(type, "type").type());
    }

    /**
     * Encodes the complete annotated field representation, including explicit null, for
     * migrations/tools.
     *
     * @param type  concrete scalar/record/adapter class; primitive class tokens reject null values
     * @param value nullable reference or boxed primitive value
     * @param <T>   value type
     * @return owned field envelope suitable for insertion under an EcsPersist key
     */
    public static <T> CompoundTag writeValue(Class<T> type, T value) {
        if (type.isPrimitive() && value == null)
            throw new EcsSerializationException("Primitive value cannot be null");
        return EcsValues.write(serializer(type), value);
    }

    /**
     * Encodes a concrete generic field representation for migrations/tools.
     *
     * @param type  concrete generic type token
     * @param value nullable reference value
     * @param <T>   value type
     * @return owned nullable field envelope
     */
    public static <T> CompoundTag writeValue(TypeRef<T> type, T value) {
        return EcsValues.write(serializer(type), value);
    }

    /**
     * Reads a complete annotated field envelope without modifying its source NBT.
     *
     * @param type concrete value class; primitive class tokens reject null values
     * @param tag  required field envelope
     * @param <T>  value type
     * @return decoded value or an explicitly stored null
     */
    @SuppressWarnings("unchecked")
    public static <T> T readValue(Class<T> type, Tag tag) {
        Object value = EcsValues.read(serializer(type), tag);
        if (type.isPrimitive() && value == null)
            throw new EcsSerializationException("Primitive value cannot be null");
        return (T) value;
    }

    /**
     * Reads a concrete generic field envelope without modifying its source NBT.
     *
     * @param type concrete generic type token
     * @param tag  required field envelope
     * @param <T>  value type
     * @return reconstructed value, possibly explicitly null
     */
    @SuppressWarnings("unchecked")
    public static <T> T readValue(TypeRef<T> type, Tag tag) {
        return (T) EcsValues.read(serializer(type), tag);
    }

    /**
     * Publishes dependencies only after the whole type schema succeeds.
     *
     * @param type exact concrete type
     * @param <T>  inferred API value type
     * @return resolved adapter
     */
    @SuppressWarnings("unchecked")
    private static <T> EcsSerializer<T> resolve(Type type) {
        var builder = new EcsSchemaBuilder(REGISTERED, RESOLVED, OVERRIDES);
        try {
            EcsSerializer<?> result = builder.resolve(type);
            builder.publish(RESOLVED, OVERRIDES);
            return (EcsSerializer<T>) result;
        } catch (Throwable failure) {
            throw SerializationFailures.at(type.getTypeName(), failure);
        }
    }

    /**
     * Checks all persisted members without constructing a component or running its callbacks.
     * WorldObjectComponentRegistry invokes this automatically for annotated components.
     *
     * @param type concrete annotated component class
     * @throws EcsSerializationException for unsupported types, inaccessible members or invalid
     *                                   annotations
     */
    public static synchronized void validateComponent(
            Class<? extends AnnotatedWorldObjectComponent> type) {
        componentSchema(Objects.requireNonNull(type, "type"));
    }

    /**
     * Returns a cached component plan and atomically publishes new dependencies after successful
     * validation.
     *
     * @param type concrete runtime component class
     * @return immutable field access plan
     */
    private static synchronized EcsComponentSchema componentSchema(Class<?> type) {
        EcsComponentSchema known = COMPONENTS.get(type);
        if (known != null) return known;
        var builder = new EcsSchemaBuilder(REGISTERED, RESOLVED, OVERRIDES);
        try {
            EcsComponentSchema result = builder.component(type);
            builder.publish(RESOLVED, OVERRIDES);
            COMPONENTS.put(type, result);
            return result;
        } catch (Throwable failure) {
            throw SerializationFailures.at(type.getName(), failure);
        }
    }

    /**
     * Writes annotated values without requiring a FieldCodec per declaration.
     *
     * @param component source component, non-null; caller must protect its state
     * @param nbt       destination compound; unknown top-level keys are retained
     */
    public static void writeComponent(AnnotatedWorldObjectComponent component, CompoundTag nbt) {
        componentSchema(component.getClass()).write(component, Objects.requireNonNull(nbt, "nbt"));
    }

    /**
     * Reads present annotated values; missing keys keep constructor defaults and input is never
     * mutated.
     *
     * @param component target component, non-null; caller must protect its state
     * @param nbt       source compound
     */
    public static void readComponent(AnnotatedWorldObjectComponent component, CompoundTag nbt) {
        componentSchema(component.getClass()).read(component, Objects.requireNonNull(nbt, "nbt"));
    }

    /**
     * Reuses only the NBT portion of an existing codec; its network methods are never called. The
     * codec retains its own malformed-tag policy; a null result for a present value is rejected.
     *
     * @param codec existing codec with stable NBT representation
     * @param <T>   value type
     * @return NBT-only adapter suitable for registration or explicit composition
     */
    public static <T> EcsSerializer<T> fromCodec(FieldCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        return EcsBuiltinSerializers.of(
                codec::toNbtElement,
                tag -> {
                    T value = codec.fromNbtElement(tag, null);
                    if (value == null)
                        throw new EcsSerializationException(
                                "Codec rejected a present value: " + codec.schemaName());
                    return value;
                });
    }
}
