package dev.sixik.sdmal.core.ecs.serialization;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Complete bounded, fingerprinted binary snapshot plan; packets never contain component NBT.
 * @param members immutable key-sorted selected component fields
 * @param fingerprint compatibility hash of the complete nested schema
 */
record EcsNetworkSchema(List<EcsNetworkMember> members, long fingerprint) {
    /** Version of this component snapshot frame. */
    private static final int FORMAT = 1;
    /** Maximum bytes in one component frame payload, excluding the four-byte length prefix. */
    static final int MAX_BYTES = 8 * 1024 * 1024;

    /**
     * Writes a length-delimited full snapshot and restores writerIndex on ordinary encoding failure.
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
            target.writeVarInt(members.size());
            for (int index = 0; index < members.size(); index++) {
                members.get(index).write(component, target);
                if (target.writerIndex() - payloadStart > MAX_BYTES)
                    throw new EcsSerializationException("ECS component packet exceeds 8 MiB");
            }
            target.setInt(start, target.writerIndex() - payloadStart);
        } catch (Throwable failure) {
            target.writerIndex(start);
            throw EcsSerializationException.at("Component wire write", failure);
        }
    }

    /**
     * Stages a complete matching frame before applying fields; failed reads restore readerIndex.
     * @param component target component, protected by its caller
     * @param source source packet; data following this frame is left unread
     */
    void read(Object component, FriendlyByteBuf source) {
        int start = source.readerIndex();
        try {
            int length = source.readInt();
            if (length < 0 || length > MAX_BYTES || length > source.readableBytes())
                throw new EcsSerializationException("Invalid or truncated ECS component frame length");
            FriendlyByteBuf payload = new FriendlyByteBuf(source.readSlice(length));
            if (payload.readVarInt() != FORMAT) throw new EcsSerializationException("Unsupported ECS wire frame version");
            if (payload.readLong() != fingerprint) throw new EcsSerializationException("ECS wire schema mismatch");
            if (payload.readVarInt() != members.size()) throw new EcsSerializationException("ECS wire field count mismatch");
            Object[] values = new Object[members.size()];
            for (int index = 0; index < members.size(); index++) values[index] = members.get(index).read(payload);
            if (payload.isReadable()) throw new EcsSerializationException("Trailing bytes in ECS component frame");
            for (int index = 0; index < members.size(); index++) members.get(index).set(component, values[index]);
        } catch (Throwable failure) {
            source.readerIndex(start);
            throw EcsSerializationException.at("Component wire read", failure);
        }
    }
}
