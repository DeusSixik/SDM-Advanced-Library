package dev.sixik.sdmal.core.ecs.serialization;

import dev.sixik.sdmal.api.ecs.serialization.EcsNetworkSerializer;

/**
 * Packet-only byte-array format; ECS component collection limits remain unchanged.
 */
final class PacketBytes {
    /**
     * Maximum byte-array payload, including room required by its enclosing message.
     */
    private static final int LIMIT = 32 * 1024 * 1024;
    /**
     * Direct bytes with checked lengths before allocation; no NBT path.
     */
    static final EcsNetworkSerializer<byte[]> FORMAT = EcsNetworkBuiltins.of("packet-bytes/varint33554432/v1",
            (buffer, bytes) -> {
                if (bytes.length > LIMIT) throw new IllegalArgumentException("Packet byte array too large");
                buffer.writeByteArray(bytes);
            }, buffer -> {
                int count = buffer.readVarInt();
                if (count < 0 || count > LIMIT || count > buffer.readableBytes())
                    throw new IllegalArgumentException("Invalid packet byte-array length");
                var bytes = new byte[count];
                buffer.readBytes(bytes);
                return bytes;
            });

    /**
     * Prevents utility construction.
     */
    private PacketBytes() {
    }
}
