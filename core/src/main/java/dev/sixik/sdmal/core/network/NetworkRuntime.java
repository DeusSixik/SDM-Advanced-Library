package dev.sixik.sdmal.core.network;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Loader bridge; application code uses Packet and PacketContext.
 */
@ApiStatus.Internal
public final class NetworkRuntime {
    /**
     * Installed native loader transport, visible to sending threads after initialization.
     */
    private static volatile Transport transport;

    /**
     * Registry discovered once before native channel activation.
     */
    private static volatile PacketRegistry registry;

    /**
     * Prevents utility construction.
     */
    private NetworkRuntime() {
    }

    /**
     * Native play-channel operations; implementations must reject missing connections.
     */
    public interface Transport {
        /**
         * @param frame owned snapshot of a SERVER packet
         */
        void toServer(byte[] frame);

        /**
         * @param player authenticated target player
         * @param frame  shared read-only CLIENT snapshot; must not be modified by the transport
         */
        void toPlayer(ServerPlayer player, byte[] frame);
    }

    /**
     * Installs the selected loader transport and discovers every mod's packet index.
     *
     * @param nativeTransport loader transport
     */
    public static synchronized void install(Transport nativeTransport) {
        if (transport != null)
            throw new IllegalStateException("Network transport already installed");
        registry = PacketRegistry.discover(NetworkRuntime.class.getClassLoader());
        transport = Objects.requireNonNull(nativeTransport, "nativeTransport");
        SDMAdvancedLibrary.LOGGER.info("Indexed {} automatic network packets", registry.size());
    }

    /**
     * Sends a client-originated packet.
     *
     * @param packet SERVER message
     */
    public static void sendToServer(Packet packet) {
        var nativeTransport = requireTransport();
        nativeTransport.toServer(registry.encode(packet, Side.SERVER));
    }

    /**
     * Sends a server-originated packet.
     *
     * @param packet CLIENT message
     * @param player connected server recipient
     */
    public static void sendToPlayer(Packet packet, Player player) {
        var recipient = requireRecipient(player);
        var nativeTransport = requireTransport();
        nativeTransport.toPlayer(recipient, registry.encode(packet, Side.CLIENT));
    }

    /**
     * Broadcasts one CLIENT snapshot to the server's live player list across all dimensions.
     *
     * @param packet CLIENT message
     * @param server running server; caller must hold its game thread
     */
    public static void sendToAll(Packet packet, MinecraftServer server) {
        sendToPlayers(
                packet, Objects.requireNonNull(server, "server").getPlayerList().getPlayers());
    }

    /**
     * Validates a recipient array before encoding once and sharing the resulting read-only frame.
     *
     * @param packet  CLIENT message
     * @param players stable array of server recipients
     */
    public static void sendToPlayers(Packet packet, Player[] players) {
        Objects.requireNonNull(players, "players");
        for (int index = 0; index < players.length; index++) requireRecipient(players[index]);
        var nativeTransport = requireTransport();
        if (players.length == 0) {
            registry.validateDirection(packet, Side.CLIENT);
            return;
        }
        var frame = registry.encode(packet, Side.CLIENT);
        for (int index = 0; index < players.length; index++)
            nativeTransport.toPlayer((ServerPlayer) players[index], frame);
    }

    /**
     * Shares one frame across a collection, using indexed loops for random-access player lists. No
     * recipient snapshot or per-player frame copy is created; callers keep membership stable.
     *
     * @param packet  CLIENT message
     * @param players stable collection of server recipients
     */
    public static void sendToPlayers(Packet packet, Collection<? extends Player> players) {
        Objects.requireNonNull(players, "players");
        if (players instanceof List<? extends Player> indexed && players instanceof RandomAccess) {
            sendToIndexedPlayers(packet, indexed);
            return;
        }
        for (var player : players) requireRecipient(player);
        var nativeTransport = requireTransport();
        if (players.isEmpty()) {
            registry.validateDirection(packet, Side.CLIENT);
            return;
        }
        var frame = registry.encode(packet, Side.CLIENT);
        for (var player : players) nativeTransport.toPlayer((ServerPlayer) player, frame);
    }

    /**
     * Routes server player lists without allocating iterator objects or temporary arrays.
     *
     * @param packet  CLIENT message
     * @param players stable random-access list of recipients
     */
    private static void sendToIndexedPlayers(Packet packet, List<? extends Player> players) {
        int count = players.size();
        for (int index = 0; index < count; index++) requireRecipient(players.get(index));
        var nativeTransport = requireTransport();
        if (count == 0) {
            registry.validateDirection(packet, Side.CLIENT);
            return;
        }
        var frame = registry.encode(packet, Side.CLIENT);
        for (int index = 0; index < count; index++)
            nativeTransport.toPlayer((ServerPlayer) players.get(index), frame);
    }

    /**
     * Rejects null or client-side recipients before a send can reach the native transport.
     *
     * @param player proposed recipient
     * @return validated server-side recipient
     * @throws IllegalArgumentException if player is null or not a ServerPlayer
     */
    private static ServerPlayer requireRecipient(Player player) {
        if (!(player instanceof ServerPlayer recipient))
            throw new IllegalArgumentException("Recipient must be a ServerPlayer");
        return recipient;
    }

    /**
     * @return initialized loader transport, or an explanatory startup error
     */
    private static Transport requireTransport() {
        var result = transport;
        if (result == null) throw new IllegalStateException("SDMAL networking has not initialized");
        return result;
    }

    /**
     * Copies a native payload before the loader releases its buffer.
     *
     * @param buffer native source
     * @return bounded logical frame
     */
    public static byte[] readFrame(FriendlyByteBuf buffer) {
        var frame = buffer.readByteArray(PacketRegistry.MAX_BYTES);
        if (buffer.isReadable())
            throw new IllegalArgumentException("Trailing bytes in native SDMAL payload");
        return frame;
    }

    /**
     * Adds a bounded native payload length prefix.
     *
     * @param buffer destination native buffer
     * @param frame  owned logical frame
     */
    public static void writeFrame(FriendlyByteBuf buffer, byte[] frame) {
        if (frame.length == 0 || frame.length > PacketRegistry.MAX_BYTES)
            throw new IllegalArgumentException("Invalid frame size");
        buffer.writeByteArray(frame);
    }

    /**
     * Schedules decoding and handling on the receiving game executor. A null player drops stale
     * work after disconnect; bad input never reaches handle. Handler failures are logged
     * separately.
     *
     * @param frame    bounded copied native payload
     * @param side     authoritative receiving side
     * @param player   connection-bound player supplier, null after disconnect or connection
     *                 replacement
     * @param executor receiving game executor
     * @param reject   loader disconnect callback for malformed input
     */
    public static void receive(
            byte[] frame,
            Side side,
            Supplier<Player> player,
            Executor executor,
            Consumer<String> reject) {
        dispatch(registry, frame, side, player, executor, reject);
    }

    /**
     * Executes a receive against a particular registry; also used by transport-independent tests.
     *
     * @param packets  logical formats
     * @param frame    bounded copied bytes
     * @param side     receiving side
     * @param player   connection-bound player supplier
     * @param executor game executor
     * @param reject   malformed input callback
     */
    static void dispatch(
            PacketRegistry packets,
            byte[] frame,
            Side side,
            Supplier<Player> player,
            Executor executor,
            Consumer<String> reject) {
        executor.execute(
                () -> {
                    var recipient = player.get();
                    if (recipient == null) return;
                    Packet packet;
                    try {
                        packet = packets.decode(frame, side);
                    } catch (RuntimeException failure) {
                        SDMAdvancedLibrary.LOGGER.warn(
                                "Rejected SDMAL packet on {}: {}", side, failure.getMessage());
                        reject.accept("Invalid or incompatible SDMAL packet");
                        return;
                    }
                    try {
                        packet.handle(new PacketContext(side, recipient, executor));
                    } catch (RuntimeException failure) {
                        SDMAdvancedLibrary.LOGGER.error(
                                "Network packet handler failed: {}",
                                packet.getClass().getName(),
                                failure);
                    }
                });
    }
}
