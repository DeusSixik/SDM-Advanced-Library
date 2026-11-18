package dev.sixik.sdmal.network;

import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.core.network.NetworkPayload;
import dev.sixik.sdmal.core.network.NetworkRuntime;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Modern NeoForge payload registration and native play transport.
 */
public final class NeoForgeNetwork implements NetworkRuntime.Transport {
    /** @return local client connection; unavailable in a dedicated-server process */
    @Override public NetworkRuntime.ClientConnection serverConnection() {
        if (!client) throw new IllegalStateException("No local NeoForge client connection");
        return dev.sixik.sdmal.core.network.ClientNetwork.serverConnection();
    }
    /**
     * Whether this process has a physical client, captured without loading client classes.
     */
    private final boolean client;
    /**
     * Creates the loader transport.
     * @param client whether this process can originate client packets
     */
    private NeoForgeNetwork(boolean client) { this.client = client; }

    /**
     * Discovers packet indices before payload registration.
     * @param client whether this process includes a physical client
     */
    public static void initialize(boolean client) {
        NetworkRuntime.install(new NeoForgeNetwork(client));
    }

    /**
     * Registers one envelope in both play directions; the logical side is checked inside it.
     *
     * @param event mod payload registration event
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playBidirectional(
                        NetworkPayload.TYPE,
                        NetworkPayload.CODEC,
                        (payload, context) -> {
                            Side side =
                                    context.flow() == PacketFlow.SERVERBOUND
                                            ? Side.SERVER
                                            : Side.CLIENT;
                            var connection = context.connection();
                            NetworkRuntime.receive(
                                    payload.frame(),
                                    side,
                                    () -> connection.isConnected() ? context.player() : null,
                                    context::enqueueWork,
                                    reason -> context.disconnect(Component.literal(reason)), connection);
                        });
    }

    /**
     * @param frame encoded SERVER message
     */
    @Override
    public void toServer(byte[] frame) {
        if (!client)
            throw new IllegalStateException("No local NeoForge client transport");
        NeoForgeClientNetwork.send(frame);
    }

    /**
     * @param player connected server recipient
     * @param frame encoded CLIENT message
     */
    @Override
    public void toPlayer(ServerPlayer player, byte[] frame) {
        PacketDistributor.sendToPlayer(player, new NetworkPayload(frame));
    }
}
