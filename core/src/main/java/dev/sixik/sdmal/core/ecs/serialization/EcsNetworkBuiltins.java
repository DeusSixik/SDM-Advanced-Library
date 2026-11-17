package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.core.ecs.codecs.ExternFieldCodecs;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/** Direct scalar and Minecraft encodings; this catalog never constructs or writes an NBT tag. */
final class EcsNetworkBuiltins {
    /** Prevents utility construction. */
    private EcsNetworkBuiltins() { }

    /** @return independent exact-class catalog with immutable binary format names */
    static Map<Type, EcsNetworkSerializer<?>> create() {
        Map<Type, EcsNetworkSerializer<?>> result = new LinkedHashMap<>();
        result.put(Boolean.class, of("bool/u8/v1", (buffer, value) -> buffer.writeByte(value ? 1 : 0), buffer -> {
            int value = buffer.readUnsignedByte();
            if (value > 1) throw new EcsSerializationException("Expected zero/one boolean");
            return value == 1;
        }));
        result.put(Byte.class, of("i8/v1", (buffer, value) -> buffer.writeByte(value), FriendlyByteBuf::readByte));
        result.put(Short.class, of("i16be/v1", (buffer, value) -> buffer.writeShort(value), FriendlyByteBuf::readShort));
        result.put(Character.class, of("char/u16be/v1", (buffer, value) -> buffer.writeChar(value), FriendlyByteBuf::readChar));
        result.put(Integer.class, of("i32be/v1", (buffer, value) -> buffer.writeInt(value), FriendlyByteBuf::readInt));
        result.put(Long.class, of("i64be/v1", (buffer, value) -> buffer.writeLong(value), FriendlyByteBuf::readLong));
        result.put(Float.class, of("f32be/v1", (buffer, value) -> buffer.writeFloat(value), FriendlyByteBuf::readFloat));
        result.put(Double.class, of("f64be/v1", (buffer, value) -> buffer.writeDouble(value), FriendlyByteBuf::readDouble));
        result.put(String.class, of("utf8/32767/v1", (buffer, value) -> buffer.writeUtf(value, 32767), buffer -> buffer.readUtf(32767)));
        result.put(UUID.class, of("uuid/2xi64be/v1", (buffer, value) -> buffer.writeUUID(value), buffer -> buffer.readUUID()));
        result.put(ResourceLocation.class, EcsNetworkSerializers.fromCodec(ExternFieldCodecs.resourceLocation()));
        result.put(BlockPos.class, EcsNetworkSerializers.fromCodec(ExternFieldCodecs.blockPos()));
        result.put(ChunkPos.class, EcsNetworkSerializers.fromCodec(ExternFieldCodecs.chunkPos()));
        result.put(SectionPos.class, EcsNetworkSerializers.fromCodec(ExternFieldCodecs.sectionPos()));
        result.put(byte[].class, of("bytes/varint65536/v1", (buffer, value) -> {
            EcsNetworkValues.checkSize(value.length); buffer.writeVarInt(value.length); buffer.writeBytes(value);
        }, buffer -> {
            byte[] value = new byte[EcsNetworkValues.readSize(buffer, 1)]; buffer.readBytes(value); return value;
        }));
        result.put(int[].class, of("ints/varint65536/i32be/v1", (buffer, value) -> {
            EcsNetworkValues.checkSize(value.length); buffer.writeVarInt(value.length);
            for (int item : value) buffer.writeInt(item);
        }, buffer -> {
            int[] value = new int[EcsNetworkValues.readSize(buffer, 4)];
            for (int index = 0; index < value.length; index++) value[index] = buffer.readInt();
            return value;
        }));
        result.put(long[].class, of("longs/varint65536/i64be/v1", (buffer, value) -> {
            EcsNetworkValues.checkSize(value.length); buffer.writeVarInt(value.length);
            for (long item : value) buffer.writeLong(item);
        }, buffer -> {
            long[] value = new long[EcsNetworkValues.readSize(buffer, 8)];
            for (int index = 0; index < value.length; index++) value[index] = buffer.readLong();
            return value;
        }));
        return result;
    }

    /**
     * Captures stateless native functions and a fixed binary layout identifier.
     * @param schema stable format identifier
     * @param writer direct binary encoder
     * @param reader direct binary decoder
     * @param <T> non-null value type
     * @return immutable function adapter
     */
    static <T> EcsNetworkSerializer<T> of(String schema, BiConsumer<FriendlyByteBuf, T> writer, Function<FriendlyByteBuf, T> reader) {
        return new EcsNetworkSerializer<>() {
            /**
             * Encodes one non-null value directly.
             * @param buffer destination buffer
             * @param value non-null source value
             */
            @Override public void write(FriendlyByteBuf buffer, T value) { writer.accept(buffer, value); }
            /**
             * Decodes one value directly.
             * @param buffer bounded source buffer
             * @return reconstructed non-null value
             */
            @Override public T read(FriendlyByteBuf buffer) { return reader.apply(buffer); }
            /** @return frozen native format identifier */
            @Override public String schemaName() { return schema; }
        };
    }
}
