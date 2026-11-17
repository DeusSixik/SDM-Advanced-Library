package dev.sixik.sdmal.api.network;

import dev.sixik.sdmal.core.network.NetworkRuntime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;

/**
 * Automatically serialized packet class or record annotated with {@code @NetworkPacket}. All
 * non-static, non-transient fields are transmitted; {@code @EcsSync} supplies optional stable
 * aliases.
 */
public interface Packet {
    /**
     * Handles a decoded message on the receiving game thread. Validate permissions and world data
     * here.
     *
     * @param context authoritative connection context
     */
    void handle(PacketContext context);

    /**
     * Sends a SERVER packet through the local client's active play connection.
     *
     * @throws IllegalStateException if the direction, connection or registration is invalid
     */
    default void send() {
        NetworkRuntime.sendToServer(this);
    }

    /**
     * Sends a CLIENT packet to a server-side player; encoding snapshots fields before returning.
     *
     * @param player connected ServerPlayer recipient
     * @throws IllegalArgumentException if player is not server-side
     * @throws IllegalStateException if the packet direction or registration is invalid
     */
    default void send(Player player) {
        NetworkRuntime.sendToPlayer(this, player);
    }

    /**
     * Sends a CLIENT packet to every player currently connected to the given server, in all
     * dimensions. Encodes one shared snapshot. Call on the server thread so its player list remains
     * stable.
     *
     * @param server running Minecraft server
     * @throws NullPointerException if server is null
     * @throws IllegalStateException if the packet direction or registration is invalid
     */
    default void send(MinecraftServer server) {
        NetworkRuntime.sendToAll(this, server);
    }

    /**
     * Sends a CLIENT packet to each array entry using one encoded snapshot. Validates every
     * recipient before sending; repeated entries receive repeated messages. Do not modify the array
     * during send.
     *
     * @param players server-side recipients; may be empty
     * @throws NullPointerException if the array is null
     * @throws IllegalArgumentException if an entry is null or is not a ServerPlayer
     * @throws IllegalStateException if the packet direction or registration is invalid
     */
    default void send(Player[] players) {
        NetworkRuntime.sendToPlayers(this, players);
    }

    /**
     * Sends a CLIENT packet to each collection entry using one encoded snapshot. Accepts
     * collections of ServerPlayer as well as Player. Do not modify the collection during send.
     *
     * @param players server-side recipients; may be empty and may contain repeated recipients
     * @throws NullPointerException if the collection is null
     * @throws IllegalArgumentException if an entry is null or is not a ServerPlayer
     * @throws IllegalStateException if the packet direction or registration is invalid
     */
    default void send(Collection<? extends Player> players) {
        NetworkRuntime.sendToPlayers(this, players);
    }
}
