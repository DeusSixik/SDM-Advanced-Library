package dev.sixik.sdmal.network;

import dev.sixik.sdmal.core.network.NetworkPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only sending helper for the NeoForge 1.21 play API.
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
        PacketDistributor.sendToServer(new NetworkPayload(frame));
    }
}
