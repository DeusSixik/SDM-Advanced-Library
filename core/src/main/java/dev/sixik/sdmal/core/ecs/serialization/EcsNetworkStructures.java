package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.annotations.EcsData;
import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Native structural encodings for enums, exact generic collections and EcsData records.
 */
final class EcsNetworkStructures {
    /**
     * Prevents utility construction.
     */
    private EcsNetworkStructures() {
    }

    /**
     * Resolves a structured binary type without consulting NBT adapters or tags.
     *
     * @param type    exact concrete type
     * @param builder finite binary-schema resolution context
     * @return native adapter
     */
    static EcsNetworkSerializer<?> build(Type type, EcsNetworkSchemaBuilder builder) {
        Class<?> raw = EcsTypes.raw(type);
        if (raw.isEnum()) return enumeration(raw);
        if (type instanceof ParameterizedType generic) {
            Type[] arguments = generic.getActualTypeArguments();
            if (raw == List.class) return list(builder.resolve(arguments[0]));
            if (raw == Map.class)
                return map(builder.resolve(arguments[0]), builder.resolve(arguments[1]));
            if (raw == Optional.class) return optional(builder.resolve(arguments[0]));
        }
        if (raw.isRecord() && raw.isAnnotationPresent(EcsData.class)) return record(type, builder);
        throw new EcsSerializationException(
                "Unsupported ECS network type "
                        + type.getTypeName()
                        + "; use @EcsData records or register an EcsNetworkSerializer");
    }

    /**
     * Uses stable enum names and fingerprints the set of allowed values.
     *
     * @param type enum class
     * @return named enum format
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EcsNetworkSerializer<?> enumeration(Class<?> type) {
        String[] names =
                Arrays.stream(type.getEnumConstants())
                        .map(value -> ((Enum<?>) value).name())
                        .sorted()
                        .toArray(String[]::new);
        return EcsNetworkBuiltins.<Enum>of(
                EcsWireSchema.describe("enum/utf8/v1", names),
                (buffer, value) -> buffer.writeUtf(value.name(), 32767),
                buffer -> Enum.valueOf((Class) type, buffer.readUtf(32767)));
    }

    /**
     * Encodes a bounded list length followed by nullable binary elements.
     *
     * @param element exact non-null element format
     * @return list format restoring mutable ArrayLists
     */
    private static EcsNetworkSerializer<List<?>> list(EcsNetworkSerializer<?> element) {
        return EcsNetworkBuiltins.of(
                EcsWireSchema.describe("list/65536/nullable/v1", element.schemaName()),
                (buffer, value) -> {
                    EcsNetworkValues.checkSize(value.size());
                    buffer.writeVarInt(value.size());
                    for (int index = 0; index < value.size(); index++)
                        EcsNetworkValues.write(element, buffer, value.get(index), true);
                },
                buffer -> {
                    int count = EcsNetworkValues.readSize(buffer, 1);
                    List<Object> values = new ObjectArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        try {
                            values.add(EcsNetworkValues.read(element, buffer, true));
                        } catch (Throwable failure) {
                            throw SerializationFailures.at("list[" + index + "]", failure);
                        }
                    }
                    return values;
                });
    }

    /**
     * Encodes binary keys/values independently; decoded duplicate keys are rejected.
     *
     * @param key   exact key format
     * @param value exact value format
     * @return insertion-order map format
     */
    private static EcsNetworkSerializer<Map<?, ?>> map(
            EcsNetworkSerializer<?> key, EcsNetworkSerializer<?> value) {
        return EcsNetworkBuiltins.of(
                EcsWireSchema.describe(
                        "map/65536/nullable/v1", key.schemaName(), value.schemaName()),
                (buffer, values) -> {
                    EcsNetworkValues.checkSize(values.size());
                    buffer.writeVarInt(values.size());
                    for (var entry : values.entrySet()) {
                        EcsNetworkValues.write(key, buffer, entry.getKey(), true);
                        EcsNetworkValues.write(value, buffer, entry.getValue(), true);
                    }
                },
                buffer -> {
                    int count = EcsNetworkValues.readSize(buffer, 2);
                    Map<Object, Object> values = new Object2ObjectLinkedOpenHashMap<>();
                    for (int index = 0; index < count; index++) {
                        try {
                            Object decodedKey = EcsNetworkValues.read(key, buffer, true);
                            Object decodedValue = EcsNetworkValues.read(value, buffer, true);
                            if (values.containsKey(decodedKey))
                                throw new EcsSerializationException(
                                        "Duplicate decoded wire map key");
                            values.put(decodedKey, decodedValue);
                        } catch (Throwable failure) {
                            throw SerializationFailures.at("map[" + index + "]", failure);
                        }
                    }
                    return values;
                });
    }

    /**
     * Distinguishes an empty Optional from the enclosing reference's explicit null.
     *
     * @param element contained non-null value format
     * @return optional binary format
     */
    private static EcsNetworkSerializer<Optional<?>> optional(EcsNetworkSerializer<?> element) {
        return EcsNetworkBuiltins.of(
                EcsWireSchema.describe("optional/nullable/v1", element.schemaName()),
                (buffer, value) ->
                        EcsNetworkValues.write(element, buffer, value.orElse(null), true),
                buffer -> Optional.ofNullable(EcsNetworkValues.read(element, buffer, true)));
    }

    /**
     * Builds direct binary fields in canonical constructor order, reusing EcsPersist keys when
     * available. Every record argument is encoded; selecting a record field selects its complete
     * value.
     *
     * @param type    concrete record type
     * @param builder current binary resolution context
     * @return record binary adapter invoking canonical constructor validation
     */
    private static EcsNetworkSerializer<?> record(Type type, EcsNetworkSchemaBuilder builder) {
        Class<?> raw = EcsTypes.raw(type);
        try {
            var components = raw.getRecordComponents();
            EcsNetworkMember[] members = new EcsNetworkMember[components.length];
            Class<?>[] parameters = new Class<?>[components.length];
            String[] descriptions = new String[components.length];
            Set<String> keys = new ObjectOpenHashSet<>();
            var bindings = EcsTypes.bindings(type);
            for (int index = 0; index < components.length; index++) {
                var component = components[index];
                var member =
                        builder.member(
                                raw.getDeclaredField(component.getName()),
                                EcsTypes.substitute(component.getGenericType(), bindings));
                if (!keys.add(member.key()))
                    throw new EcsSerializationException(
                            "Duplicate record wire key " + member.key());
                members[index] = member;
                parameters[index] = component.getType();
                descriptions[index] = member.schemaName();
            }
            var constructor =
                    MethodHandles.privateLookupIn(raw, MethodHandles.lookup())
                            .findConstructor(raw, MethodType.methodType(void.class, parameters))
                            .asSpreader(Object[].class, components.length)
                            .asType(MethodType.methodType(Object.class, Object[].class));
            return EcsNetworkBuiltins.of(
                    EcsWireSchema.describe("record/v1", descriptions),
                    (buffer, value) -> {
                        for (EcsNetworkMember member : members) member.write(value, buffer);
                    },
                    buffer -> {
                        Object[] arguments = new Object[members.length];
                        for (int index = 0; index < members.length; index++)
                            arguments[index] = members[index].read(buffer);
                        try {
                            return (Object) constructor.invokeExact(arguments);
                        } catch (Throwable failure) {
                            throw SerializationFailures.at(
                                    raw.getName() + " canonical constructor", failure);
                        }
                    });
        } catch (ReflectiveOperationException failure) {
            throw SerializationFailures.at(type.getTypeName(), failure);
        }
    }
}
