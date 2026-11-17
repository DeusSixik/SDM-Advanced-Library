package dev.sixik.sdmal.core.network;

import dev.sixik.sdmal.api.network.Side;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.Executor;

/**
 * Client-only bridge, referenced only after clientbound reception is established.
 */
@ApiStatus.Internal
public final class ClientNetwork {
    /**
     * Prevents utility construction.
     */
    private ClientNetwork() {
    }

    /**
     * Drops queued work after disconnect or connection replacement.
     *
     * @param frame      owned received frame
     * @param connection receiving client connection
     * @param executor   receiving game executor
     */
    public static void receive(byte[] frame, Connection connection, Executor executor) {
        NetworkRuntime.receive(
                frame,
                Side.CLIENT,
                () -> {
                    var client = Minecraft.getInstance();
                    var listener = client.getConnection();
                    if (listener == null
                            || listener.getConnection() != connection
                            || !connection.isConnected()) return null;
                    return client.player;
                },
                executor,
                reason -> connection.disconnect(Component.literal(reason)));
    }
}
