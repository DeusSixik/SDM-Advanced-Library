package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsSerializationException;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Complete bounded, fingerprinted binary snapshot plan; packets never contain component NBT.
 *
 * @param members immutable key-sorted selected component fields
 * @param fingerprint compatibility hash of the complete nested schema
 */
record EcsNetworkSchema(EcsNetworkMember[] members, long fingerprint) {
    /**
     * Version of this component snapshot frame.
     */
    private static final int FORMAT = 1;

    /**
     * Maximum bytes in one component frame payload, excluding the four-byte length prefix.
     */
    static final int MAX_BYTES = 8 * 1024 * 1024;

    /**
     * Writes a length-delimited full snapshot and restores writerIndex on ordinary encoding
     * failure.
     *
     * @param component source component, protected by its caller
     * @param target output packet, not released
     */
    void write(Object component, FriendlyByteBuf target) {
        int start = target.writerIndex();
        try {
            target.writeInt(0);
            int payloadStart = target.writerIndex();
            target.writeVarInt(FORMAT);
            target.writeLong(fingerprint);
            target.writeVarInt(members.length);
            for (int index = 0; index < members.length; index++) {
                members[index].write(component, target);
                if (target.writerIndex() - payloadStart > MAX_BYTES)
                    throw new EcsSerializationException("ECS component packet exceeds 8 MiB");
            }
            target.setInt(start, target.writerIndex() - payloadStart);
        } catch (Throwable failure) {
            target.writerIndex(start);
            throw SerializationFailures.at("Component wire write", failure);
        }
    }

    /**
     * Stages a complete matching frame before applying fields; failed reads restore readerIndex.
     *
     * @param component target component, protected by its caller
     * @param source source packet; data following this frame is left unread
     */
    void read(Object component, FriendlyByteBuf source) {
        int start = source.readerIndex();
        try {
            int length = source.readInt();
            if (length < 0 || length > MAX_BYTES || length > source.readableBytes())
                throw new EcsSerializationException(
                        "Invalid or truncated ECS component frame length");
            FriendlyByteBuf payload = new FriendlyByteBuf(source.readSlice(length));
            if (payload.readVarInt() != FORMAT)
                throw new EcsSerializationException("Unsupported ECS wire frame version");
            if (payload.readLong() != fingerprint)
                throw new EcsSerializationException("ECS wire schema mismatch");
            if (payload.readVarInt() != members.length)
                throw new EcsSerializationException("ECS wire field count mismatch");
            Object[] values = new Object[members.length];
            for (int index = 0; index < members.length; index++)
                values[index] = members[index].read(payload);
            if (payload.isReadable())
                throw new EcsSerializationException("Trailing bytes in ECS component frame");
            for (int index = 0; index < members.length; index++)
                members[index].set(component, values[index]);
        } catch (Throwable failure) {
            source.readerIndex(start);
            throw SerializationFailures.at("Component wire read", failure);
        }
    }
}
