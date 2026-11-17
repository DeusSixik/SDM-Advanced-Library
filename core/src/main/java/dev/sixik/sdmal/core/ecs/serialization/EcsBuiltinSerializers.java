package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.codecs.ExternFieldCodecs;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializer;
import dev.sixik.sdmal.api.ecs.sync.FieldCodec;
import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.core.ecs.codecs.NbtAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Adapts existing scalar/Minecraft codecs to NBT-only persistence without changing their wire
 * contracts.
 */
final class EcsBuiltinSerializers {
    /**
     * Prevents utility construction.
     */
    private EcsBuiltinSerializers() {
    }

    /**
     * @return independent catalog of immutable built-in scalar adapter registrations
     */
    static Map<Type, EcsSerializer<?>> create() {
        Map<Type, EcsSerializer<?>> result = new LinkedHashMap<>();
        result.put(
                Boolean.class,
                owned(
                        value -> ByteTag.valueOf(value),
                        tag -> {
                            if (!(tag instanceof ByteTag flag))
                                throw new EcsSerializationException("Expected boolean byte");
                            byte value = NbtAccess.number(flag).byteValue();
                            if (value != 0 && value != 1)
                                throw new EcsSerializationException("Expected zero/one boolean");
                            return value == 1;
                        }));
        result.put(Byte.class, codec(FieldCodecs.byteValue(), ByteTag.class));
        result.put(
                Short.class,
                owned(
                        ShortTag::valueOf,
                        tag -> {
                            if (!(tag instanceof ShortTag number))
                                throw new EcsSerializationException("Expected short");
                            return NbtAccess.number(number).shortValue();
                        }));
        result.put(
                Character.class,
                owned(
                        value -> IntTag.valueOf(value),
                        tag -> {
                            if (!(tag instanceof IntTag number))
                                throw new EcsSerializationException("Expected character int");
                            int value = NbtAccess.number(number).intValue();
                            if (value < 0 || value > Character.MAX_VALUE)
                                throw new EcsSerializationException("Character out of bounds");
                            return (char) value;
                        }));
        result.put(Integer.class, codec(FieldCodecs.intValue(), IntTag.class));
        result.put(Long.class, codec(FieldCodecs.longValue(), LongTag.class));
        result.put(Float.class, codec(FieldCodecs.floatValue(), FloatTag.class));
        result.put(Double.class, codec(FieldCodecs.doubleValue(), DoubleTag.class));
        result.put(String.class, codec(FieldCodecs.string(), StringTag.class));
        result.put(UUID.class, codec(FieldCodecs.uuid(), IntArrayTag.class));
        result.put(CompoundTag.class, codec(FieldCodecs.compound(), CompoundTag.class));
        result.put(
                ResourceLocation.class,
                codec(ExternFieldCodecs.resourceLocation(), StringTag.class));
        result.put(BlockPos.class, codec(ExternFieldCodecs.blockPos(), LongTag.class));
        result.put(ChunkPos.class, codec(ExternFieldCodecs.chunkPos(), LongTag.class));
        result.put(SectionPos.class, codec(ExternFieldCodecs.sectionPos(), LongTag.class));
        result.put(
                byte[].class,
                owned(
                        value -> new ByteArrayTag(value.clone()),
                        tag -> {
                            if (!(tag instanceof ByteArrayTag array))
                                throw new EcsSerializationException("Expected byte array");
                            return array.getAsByteArray().clone();
                        }));
        result.put(
                int[].class,
                owned(
                        value -> new IntArrayTag(value.clone()),
                        tag -> {
                            if (!(tag instanceof IntArrayTag array))
                                throw new EcsSerializationException("Expected int array");
                            return array.getAsIntArray().clone();
                        }));
        result.put(
                long[].class,
                owned(
                        value -> new LongArrayTag(value.clone()),
                        tag -> {
                            if (!(tag instanceof LongArrayTag array))
                                throw new EcsSerializationException("Expected long array");
                            return array.getAsLongArray().clone();
                        }));
        return result;
    }

    /**
     * Creates a strict NBT-tag adapter around a compatible existing codec.
     *
     * @param codec   existing NBT/network codec
     * @param tagType expected on-disk tag class
     * @param <T>     value type
     * @return NBT-only adapter
     */
    private static <T> EcsSerializer<T> codec(FieldCodec<T> codec, Class<? extends Tag> tagType) {
        EcsSerializer<T> adapted = NbtSerialization.fromCodec(codec);
        return owned(
                adapted::write,
                tag -> {
                    if (!tagType.isInstance(tag))
                        throw new EcsSerializationException("Expected " + tagType.getSimpleName());
                    return adapted.read(tag);
                });
    }

    /**
     * Creates an internal adapter whose output is owned and whose decoder is read-only.
     *
     * @param writer independently owned tag encoder
     * @param reader read-only tag decoder
     * @param <T>    represented value type
     * @return adapter whose redundant boundary copy may be omitted
     */
    static <T> EcsSerializer<T> owned(Function<T, Tag> writer, Function<Tag, T> reader) {
        return new OwnedNbtSerializer<>(writer, reader);
    }

    /**
     * Captures immutable conversion functions for a non-null value.
     *
     * @param writer NBT encoder
     * @param reader NBT decoder
     * @param <T>    value type
     * @return stateless function adapter
     */
    static <T> EcsSerializer<T> of(Function<T, Tag> writer, Function<Tag, T> reader) {
        return new EcsSerializer<>() {
            /**
             * Encodes one non-null value.
             *
             * @param value source value
             * @return encoded tag
             */
            @Override
            public Tag write(T value) {
                return writer.apply(value);
            }

            /**
             * Decodes a previously validated tag.
             *
             * @param tag source tag
             * @return decoded value
             */
            @Override
            public T read(Tag tag) {
                return reader.apply(tag);
            }
        };
    }
}
