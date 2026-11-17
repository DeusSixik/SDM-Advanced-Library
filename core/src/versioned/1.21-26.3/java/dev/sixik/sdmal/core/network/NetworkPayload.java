package dev.sixik.sdmal.core.network;

import dev.sixik.sdmal.SDMAdvancedLibrary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import org.jetbrains.annotations.ApiStatus;

/**
 * Single native play envelope; logical packet ids and schemas live inside the bounded frame.
 *
 * @param frame owned encoded logical message
 */
@ApiStatus.Internal
public record NetworkPayload(byte[] frame) implements CustomPacketPayload {
    /**
     * Native channel identifier shared by both play directions.
     */
    public static final Type<NetworkPayload> TYPE = new Type<>(SDMAdvancedLibrary.id("packets"));

    /**
     * Native framing format, independent of logical packet types.
     */
    public static final StreamCodec<FriendlyByteBuf, NetworkPayload> CODEC =
            StreamCodec.of(
                    (buffer, payload) -> NetworkRuntime.writeFrame(buffer, payload.frame()),
                    buffer -> new NetworkPayload(NetworkRuntime.readFrame(buffer)));

    /**
     * @return the common SDMAL play payload type
     */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
