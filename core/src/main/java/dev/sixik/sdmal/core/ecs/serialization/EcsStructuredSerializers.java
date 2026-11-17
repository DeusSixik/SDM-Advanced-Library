package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.annotations.EcsData;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds collection and record adapters once, with explicit envelopes and bounded collection
 * lengths.
 */
final class EcsStructuredSerializers {
    /**
     * Maximum list elements or map entries per annotated value, inclusive.
     */
    private static final int MAX_ELEMENTS = 65_536;

    /**
     * Prevents utility construction.
     */
    private EcsStructuredSerializers() {}

    /**
     * Builds one structural adapter using exact generic argument resolution.
     *
     * @param type concrete declared value type
     * @param builder current finite-schema resolution context
     * @return structural value adapter
     * @throws EcsSerializationException for unsupported classes or recursive schemas
     */
    static EcsSerializer<?> build(Type type, EcsSchemaBuilder builder) {
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
                "Unsupported ECS value type "
                        + type.getTypeName()
                        + "; annotate a record with @EcsData or register an EcsSerializer");
    }

    /**
     * Encodes enum names rather than unstable ordinals.
     *
     * @param type enum class
     * @return name-based enum adapter
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EcsSerializer<?> enumeration(Class<?> type) {
        return EcsBuiltinSerializers.<Enum>owned(
                value -> StringTag.valueOf(value.name()),
                tag -> {
                    if (!(tag instanceof StringTag text))
                        throw new EcsSerializationException("Expected enum name");
                    return Enum.valueOf((Class) type, NbtAccess.string(text));
                });
    }

    /**
     * Encodes homogeneous envelopes so nullable/custom NBT element shapes never violate ListTag
     * typing.
     *
     * @param element concrete element serializer
     * @return list adapter restoring mutable ArrayLists
     */
    private static EcsSerializer<List<?>> list(EcsSerializer<?> element) {
        return EcsBuiltinSerializers.owned(
                values -> {
                    checkSize(values.size());
                    ListTag tag = new ListTag();
                    for (int index = 0; index < values.size(); index++) {
                        try {
                            tag.add(EcsValues.write(element, values.get(index)));
                        } catch (Throwable failure) {
                            throw SerializationFailures.at("[" + index + "]", failure);
                        }
                    }
                    return tag;
                },
                tag -> {
                    ListTag entries = entries(tag);
                    List<Object> values = new ArrayList<>(entries.size());
                    for (int index = 0; index < entries.size(); index++) {
                        try {
                            values.add(EcsValues.read(element, entries.get(index)));
                        } catch (Throwable failure) {
                            throw SerializationFailures.at("[" + index + "]", failure);
                        }
                    }
                    return values;
                });
    }

    /**
     * Encodes keys as values, allowing UUID/record keys without string coercion or duplicate loss.
     *
     * @param key concrete key serializer
     * @param value concrete value serializer
     * @return insertion-order map adapter restoring mutable LinkedHashMaps
     */
    private static EcsSerializer<Map<?, ?>> map(EcsSerializer<?> key, EcsSerializer<?> value) {
        return EcsBuiltinSerializers.owned(
                values -> {
                    checkSize(values.size());
                    ListTag entries = new ListTag();
                    for (var entry : values.entrySet()) {
                        CompoundTag tag = new CompoundTag();
                        tag.put("k", EcsValues.write(key, entry.getKey()));
                        tag.put("v", EcsValues.write(value, entry.getValue()));
                        entries.add(tag);
                    }
                    return entries;
                },
                tag -> {
                    ListTag entries = entries(tag);
                    Map<Object, Object> values = new LinkedHashMap<>();
                    for (int index = 0; index < entries.size(); index++) {
                        try {
                            if (!(entries.get(index) instanceof CompoundTag entry))
                                throw new EcsSerializationException("Expected map entry compound");
                            Object decodedKey = EcsValues.read(key, entry.get("k"));
                            Object decodedValue = EcsValues.read(value, entry.get("v"));
                            if (values.containsKey(decodedKey))
                                throw new EcsSerializationException("Duplicate decoded map key");
                            values.put(decodedKey, decodedValue);
                        } catch (Throwable failure) {
                            throw SerializationFailures.at("map[" + index + "]", failure);
                        }
                    }
                    return values;
                });
    }

    /**
     * Distinguishes Optional.empty from both a missing component field and a null Optional
     * reference.
     *
     * @param element exact contained-value serializer
     * @return optional adapter
     */
    private static EcsSerializer<Optional<?>> optional(EcsSerializer<?> element) {
        return EcsBuiltinSerializers.owned(
                value -> EcsValues.write(element, value.orElse(null)),
                tag -> Optional.ofNullable(EcsValues.read(element, tag)));
    }

    /**
     * Builds private field handles and a canonical record constructor, preserving constructor
     * validation.
     *
     * @param type concrete record type, possibly specialized with generic arguments
     * @param builder current resolution context
     * @return record adapter with all constructor arguments required in its saved data
     */
    private static EcsSerializer<?> record(Type type, EcsSchemaBuilder builder) {
        Class<?> raw = EcsTypes.raw(type);
        try {
            var components = raw.getRecordComponents();
            List<EcsMember> members = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            var bindings = EcsTypes.bindings(type);
            for (int index = 0; index < components.length; index++) {
                var component = components[index];
                if (!component.isAnnotationPresent(EcsPersist.class))
                    throw new EcsSerializationException(
                            raw.getName() + "." + component.getName() + " requires @EcsPersist");
                var member =
                        builder.member(
                                raw.getDeclaredField(component.getName()),
                                EcsTypes.substitute(component.getGenericType(), bindings));
                if (!keys.add(member.key()))
                    throw new EcsSerializationException("Duplicate record key " + member.key());
                members.add(member);
                parameterTypes[index] = component.getType();
            }
            var lookup = MethodHandles.privateLookupIn(raw, MethodHandles.lookup());
            var constructor =
                    lookup.findConstructor(raw, MethodType.methodType(void.class, parameterTypes))
                            .asSpreader(Object[].class, components.length)
                            .asType(MethodType.methodType(Object.class, Object[].class));
            List<EcsMember> plan = List.copyOf(members);
            return EcsBuiltinSerializers.owned(
                    value -> {
                        CompoundTag tag = new CompoundTag();
                        for (EcsMember member : plan) tag.put(member.key(), member.write(value));
                        return tag;
                    },
                    tag -> {
                        if (!(tag instanceof CompoundTag record))
                            throw new EcsSerializationException(
                                    "Expected record compound " + raw.getName());
                        Object[] arguments = new Object[plan.size()];
                        for (int index = 0; index < plan.size(); index++) {
                            EcsMember member = plan.get(index);
                            if (record.get(member.key()) == null)
                                throw new EcsSerializationException(
                                        "Missing record member " + member.path());
                            arguments[index] = member.read(record.get(member.key()));
                        }
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

    /**
     * Requires a bounded list before allocating target elements.
     *
     * @param tag source list tag
     * @return validated list
     */
    private static ListTag entries(Tag tag) {
        if (!(tag instanceof ListTag entries)) throw new EcsSerializationException("Expected list");
        checkSize(entries.size());
        return entries;
    }

    /**
     * Applies the same size bound to written and read collections.
     *
     * @param size non-negative collection length
     * @throws EcsSerializationException if size exceeds the public 65536-element limit
     */
    private static void checkSize(int size) {
        if (size > MAX_ELEMENTS)
            throw new EcsSerializationException(
                    "Annotated collection exceeds " + MAX_ELEMENTS + " elements");
    }
}
