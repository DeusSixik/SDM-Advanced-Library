package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.ecs.serialization.TypeRef;
import dev.sixik.sdmal.api.ecs.sync.FieldCodec;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exact-type binary adapter registry, independent of NBT registrations. Register adapters before
 * component types or first use. Successful resolutions are immutable for the process lifetime, as
 * with NbtSerialization.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class NetworkSerialization {
    /**
     * Built-in and explicit binary formats, protected by this class's monitor.
     */
    private static final Map<Type, EcsNetworkSerializer<?>> REGISTERED =
            EcsNetworkBuiltins.create();

    /**
     * Successfully built immutable structural binary adapters.
     */
    private static final Map<Type, EcsNetworkSerializer<?>> RESOLVED = new Object2ObjectOpenHashMap<>();

    /**
     * Per-field annotation adapter instances shared after successful validation.
     */
    private static final Map<Class<? extends EcsNetworkSerializer<?>>, EcsNetworkSerializer<?>>
            OVERRIDES = new Object2ObjectOpenHashMap<>();

    /**
     * ClassValue plans avoid a global registry lock on each component packet.
     */
    private static final ClassValue<EcsNetworkSchema> COMPONENTS =
            new ClassValue<>() {
                /**
                 * Builds a schema at first validation/use, publishing adapters only after complete
                 * success.
                 *
                 * @param type concrete component class
                 * @return immutable wire plan
                 */
                @Override
                protected EcsNetworkSchema computeValue(Class<?> type) {
                    return buildComponent(type);
                }
            };

    /**
     * Prevents utility construction.
     */
    private NetworkSerialization() {
    }

    /**
     * Builds a packet's automatic field format using the same native adapters as ECS sync.
     *
     * @param type concrete class or record implementing Packet
     * @param <T>  packet type
     * @return immutable format; callers cache it after first use
     */
    public static synchronized <T> EcsNetworkSerializer<T> packetCodec(Class<T> type) {
        var builder = new EcsNetworkSchemaBuilder(REGISTERED, RESOLVED, OVERRIDES);
        var codec = PacketSchema.build(type, builder);
        builder.publish(RESOLVED, OVERRIDES);
        return codec;
    }

    /**
     * Registers one exact concrete binary type without requiring NBT methods.
     *
     * @param type       non-generic concrete class
     * @param serializer stateless native binary adapter
     * @param <T>        value type
     * @throws IllegalStateException if this type is already registered/resolved
     */
    public static synchronized <T> void register(
            Class<T> type, EcsNetworkSerializer<T> serializer) {
        registerType(type, serializer);
    }

    /**
     * Registers one concrete generic binary type, preserving its complete type arguments.
     *
     * @param type       exact generic type token
     * @param serializer stateless native binary adapter
     * @param <T>        value type
     * @throws IllegalStateException if this exact type is already registered/resolved
     */
    public static synchronized <T> void register(
            TypeRef<T> type, EcsNetworkSerializer<T> serializer) {
        registerType(Objects.requireNonNull(type, "type").type(), serializer);
    }

    /**
     * Checks registration invariants before exposing a binary format.
     *
     * @param type       concrete registration type
     * @param serializer non-null adapter with stable schema name
     */
    private static void registerType(Type type, EcsNetworkSerializer<?> serializer) {
        EcsTypes.requireConcrete(Objects.requireNonNull(type, "type"));
        type = EcsTypes.boxed(type);
        Objects.requireNonNull(serializer, "serializer");
        if (REGISTERED.containsKey(type) || RESOLVED.containsKey(type))
            throw new IllegalStateException(
                    "ECS network serializer already registered or resolved: " + type.getTypeName());
        REGISTERED.put(type, EcsNetworkSchemaBuilder.checked(type, serializer));
    }

    /**
     * Resolves a native non-null value format without an enclosing component frame.
     *
     * @param type concrete class
     * @param <T>  value type
     * @return shared binary adapter
     */
    public static synchronized <T> EcsNetworkSerializer<T> serializer(Class<T> type) {
        return resolve(type);
    }

    /**
     * Resolves a concrete generic native binary format.
     *
     * @param type captured exact type
     * @param <T>  value type
     * @return shared non-null value adapter
     */
    public static synchronized <T> EcsNetworkSerializer<T> serializer(TypeRef<T> type) {
        return resolve(Objects.requireNonNull(type, "type").type());
    }

    /**
     * Stages an entire adapter graph before freezing its dependencies.
     *
     * @param type exact value type
     * @param <T>  inferred API value type
     * @return checked adapter
     */
    @SuppressWarnings("unchecked")
    private static <T> EcsNetworkSerializer<T> resolve(Type type) {
        var builder = new EcsNetworkSchemaBuilder(REGISTERED, RESOLVED, OVERRIDES);
        try {
            var serializer = builder.resolve(type);
            builder.publish(RESOLVED, OVERRIDES);
            return (EcsNetworkSerializer<T>) serializer;
        } catch (Throwable failure) {
            throw SerializationFailures.at(type.getTypeName(), failure);
        }
    }

    /**
     * Builds a component schema under the registration monitor, without constructing its instance.
     *
     * @param type concrete annotated component class
     * @return immutable key-sorted binary plan
     */
    private static synchronized EcsNetworkSchema buildComponent(Class<?> type) {
        var builder = new EcsNetworkSchemaBuilder(REGISTERED, RESOLVED, OVERRIDES);
        try {
            var schema = builder.component(type);
            builder.publish(RESOLVED, OVERRIDES);
            return schema;
        } catch (Throwable failure) {
            throw SerializationFailures.at(type.getName(), failure);
        }
    }

    /**
     * Validates selected EcsSync fields eagerly; persistence-only fields do not need binary
     * adapters.
     *
     * @param type concrete component class
     */
    public static void validateComponent(Class<? extends AnnotatedWorldObjectComponent> type) {
        COMPONENTS.get(Objects.requireNonNull(type, "type"));
    }

    /**
     * Identifies selected keys, nullability, nested record structure and versioned binary adapters.
     *
     * @param type concrete component class
     * @return deterministic 64-bit wire compatibility fingerprint
     */
    public static long schemaFingerprint(Class<? extends AnnotatedWorldObjectComponent> type) {
        return COMPONENTS.get(type).fingerprint();
    }

    /**
     * Writes one full binary component frame, with length/version/fingerprint and no intermediate
     * NBT.
     *
     * @param component source component; caller must protect its state
     * @param buffer    caller-owned destination buffer
     */
    public static void writeComponent(
            AnnotatedWorldObjectComponent component, FriendlyByteBuf buffer) {
        COMPONENTS
                .get(component.getClass())
                .write(component, Objects.requireNonNull(buffer, "buffer"));
    }

    /**
     * Reads and stages one bounded matching frame before applying selected fields.
     *
     * @param component target component; caller must protect its state
     * @param buffer    caller-owned packet; later frames remain unread
     */
    public static void readComponent(
            AnnotatedWorldObjectComponent component, FriendlyByteBuf buffer) {
        COMPONENTS
                .get(component.getClass())
                .read(component, Objects.requireNonNull(buffer, "buffer"));
    }

    /**
     * Reuses only an existing FieldCodec's native binary methods; NBT methods are never called.
     *
     * @param codec existing wire codec with a stable schemaName
     * @param <T>   value type
     * @return network-only adapter retaining the codec's wire layout
     */
    public static <T> EcsNetworkSerializer<T> fromCodec(FieldCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        return EcsNetworkBuiltins.of(codec.schemaName(), codec::toNetwork, codec::fromNetwork);
    }
}
