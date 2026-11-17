package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;
import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Handles explicit nullable references and collection bounds without constructing any NBT tags.
 */
final class EcsNetworkValues {
    /**
     * Maximum element/entry count for one structural collection or primitive array.
     */
    static final int MAX_ELEMENTS = 65_536;

    /**
     * Prevents utility construction.
     */
    private EcsNetworkValues() {}

    /**
     * Writes primitives directly and reference values with an explicit zero/one presence byte.
     *
     * @param adapter non-null value encoder
     * @param buffer destination packet
     * @param value boxed primitive or nullable reference
     * @param nullable whether the declared type is a reference
     */
    @SuppressWarnings("unchecked")
    static void write(
            EcsNetworkSerializer<?> adapter,
            FriendlyByteBuf buffer,
            Object value,
            boolean nullable) {
        if (nullable) buffer.writeByte(value == null ? 0 : 1);
        else if (value == null)
            throw new EcsSerializationException("Primitive wire value cannot be null");
        if (value != null) ((EcsNetworkSerializer<Object>) adapter).write(buffer, value);
    }

    /**
     * Reads a strict presence flag for reference types, leaving primitive formats unwrapped.
     *
     * @param adapter non-null value decoder
     * @param buffer bounded source packet
     * @param nullable whether the declared type is a reference
     * @return reconstructed value, possibly null for a reference
     */
    static Object read(EcsNetworkSerializer<?> adapter, FriendlyByteBuf buffer, boolean nullable) {
        if (nullable) {
            int flag = buffer.readUnsignedByte();
            if (flag == 0) return null;
            if (flag != 1) throw new EcsSerializationException("Invalid wire presence flag");
        }
        Object value = adapter.read(buffer);
        if (value == null)
            throw new EcsSerializationException(
                    "Network adapter returned null for a present value");
        return value;
    }

    /**
     * Checks a collection length before encoding it.
     *
     * @param size non-negative length
     * @throws EcsSerializationException for lengths outside [0, 65536]
     */
    static void checkSize(int size) {
        if (size < 0 || size > MAX_ELEMENTS)
            throw new EcsSerializationException("Wire collection length out of bounds: " + size);
    }

    /**
     * Checks lengths and a minimum remaining payload before allocating a collection or array.
     *
     * @param buffer bounded source buffer
     * @param minimumBytes minimum encoded bytes per element, positive
     * @return validated element count
     */
    static int readSize(FriendlyByteBuf buffer, int minimumBytes) {
        int count = buffer.readVarInt();
        checkSize(count);
        if ((long) count * minimumBytes > buffer.readableBytes())
            throw new EcsSerializationException("Truncated wire collection");
        return count;
    }
}
