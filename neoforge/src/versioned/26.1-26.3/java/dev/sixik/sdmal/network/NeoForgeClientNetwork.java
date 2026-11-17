package dev.sixik.sdmal.network;

import dev.sixik.sdmal.core.network.NetworkPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client-only sending helper for NeoForge's separated client transport API.
 */
final class NeoForgeClientNetwork {
    /**
     * Prevents utility construction.
     */
    private NeoForgeClientNetwork() {}

    /**
     * Sends through the active local play connection.
     *
     * @param frame encoded SERVER message
     */
    static void send(byte[] frame) {
        if (Minecraft.getInstance().getConnection() == null)
            throw new IllegalStateException("No active client play connection");
        ClientPacketDistributor.sendToServer(new NetworkPayload(frame));
    }
}
