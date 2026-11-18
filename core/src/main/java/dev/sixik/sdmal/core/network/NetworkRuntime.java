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
import java.util.function.BooleanSupplier;

/**
 * Loader bridge; application code uses Packet and PacketContext.
 */
@ApiStatus.Internal
public final class NetworkRuntime {
    /**
     * Direct native frame limit, including fragmentation headers.
     */
    public static final int MAX_FRAME_BYTES = PacketRegistry.MAX_BYTES;
    /**
     * Maximum logical message assembled before decoding.
     */
    public static final int MAX_LOGICAL_BYTES = PacketRegistry.MAX_LOGICAL_BYTES;
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

        /**
         * @return current local client's connection binding; unavailable on a dedicated server
         */
        default ClientConnection serverConnection() {
            throw new IllegalStateException("No local client play connection");
        }
    }

    /**
     * Captured client connection identity and a liveness check that also rejects reconnection.
     *
     * @param identity  native Connection instance
     * @param connected true only while this exact connection remains active
     */
    public record ClientConnection(Object identity, BooleanSupplier connected) {
        /**
         * @param identity native connection @param connected exact-connection liveness check
         */
        public ClientConnection {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(connected, "connected");
        }
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
        AsyncRequestRuntime.install(NetworkRuntime.class.getClassLoader());
        transport = Objects.requireNonNull(nativeTransport, "nativeTransport");
        SDMAdvancedLibrary.LOGGER.info("Indexed {} automatic network packets", registry.size());
    }

    /**
     * Sends a client-originated packet.
     *
     * @param packet SERVER message
     */
    public static void sendToServer(Packet packet) {
        requireTransport();
        sendRawToServer(registry.encode(packet, Side.SERVER));
    }

    /**
     * Sends a server-originated packet.
     *
     * @param packet CLIENT message
     * @param player connected server recipient
     */
    public static void sendToPlayer(Packet packet, Player player) {
        var recipient = requireRecipient(player);
        requireTransport();
        sendRawToPlayer(recipient, registry.encode(packet, Side.CLIENT));
    }

    /**
     * Sends an already encoded logical frame, fragmenting it when the loader frame limit is exceeded.
     */
    static void sendRawToServer(byte[] frame) {
        var nativeTransport = requireTransport();
        NetworkFragmentation.send(frame, nativeTransport::toServer);
    }

    /**
     * Sends an already encoded logical frame to one recipient, fragmenting it when required.
     */
    static void sendRawToPlayer(ServerPlayer player, byte[] frame) {
        var nativeTransport = requireTransport();
        NetworkFragmentation.send(frame, part -> nativeTransport.toPlayer(player, part));
    }

    /**
     * @return connection-bound client endpoint for a new C2S request
     */
    static NetworkPeer serverPeer() {
        var nativeTransport = requireTransport();
        var connection = nativeTransport.serverConnection();
        return new NetworkPeer(Side.CLIENT, connection.identity(), connection.connected(),
                frame -> NetworkFragmentation.send(frame, nativeTransport::toServer));
    }

    /**
     * @param player connected recipient @return connection-bound server endpoint for a new S2C request
     */
    static NetworkPeer playerPeer(ServerPlayer player) {
        var nativeTransport = requireTransport();
        var listener = Objects.requireNonNull(player.connection, "Player has no play connection");
        return new NetworkPeer(Side.SERVER, listener, () -> {
            //? if >=1.20 {
            return player.connection == listener && listener.isAcceptingMessages();
            //?} else {
            /*return player.connection == listener && listener.connection.isConnected();
             *///?}
        }, frame -> NetworkFragmentation.send(frame, part -> nativeTransport.toPlayer(player, part)));
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
        for (int index = 0; index < players.length; index++) {
            var recipient = (ServerPlayer) players[index];
            sendEncoded(nativeTransport, recipient, frame);
        }
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
        for (var player : players) sendEncoded(nativeTransport, (ServerPlayer) player, frame);
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
        for (int index = 0; index < count; index++) {
            var recipient = (ServerPlayer) players.get(index);
            sendEncoded(nativeTransport, recipient, frame);
        }
    }

    /**
     * @param nativeTransport installed native sender @param player recipient @param frame shared encoded message
     */
    private static void sendEncoded(Transport nativeTransport, ServerPlayer player, byte[] frame) {
        if (frame.length <= MAX_FRAME_BYTES) {
            nativeTransport.toPlayer(player, frame);
            return;
        }
        NetworkFragmentation.send(frame, part -> nativeTransport.toPlayer(player, part));
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
        var frame = buffer.readByteArray(MAX_FRAME_BYTES);
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
        if (frame.length == 0 || frame.length > MAX_FRAME_BYTES)
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
     * Supplies a captured client Connection so asynchronous replies cannot target a replacement connection.
     *
     * @param frame            native frame
     * @param side             receiving side
     * @param player           connection-bound player lookup
     * @param executor         game executor
     * @param reject           malformed input callback
     * @param clientConnection native receiving connection; server endpoints use their player listener
     */
    public static void receive(byte[] frame, Side side, Supplier<Player> player, Executor executor,
                               Consumer<String> reject, Object clientConnection) {
        dispatch(registry, frame, side, player, executor, reject, clientConnection);
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
        dispatch(packets, frame, side, player, executor, reject, null);
    }

    /**
     * @param packets          discovered packet metadata
     * @param frame            native frame
     * @param side             receiving side
     * @param player           connection-bound player lookup
     * @param executor         game executor
     * @param reject           malformed input callback
     * @param clientConnection captured client connection or null for ordinary test dispatch
     */
    static void dispatch(PacketRegistry packets, byte[] frame, Side side, Supplier<Player> player,
                         Executor executor, Consumer<String> reject, Object clientConnection) {
        executor.execute(
                () -> {
                    var recipient = player.get();
                    if (recipient == null) return;
                    byte[] assembled;
                    try {
                        Object identity = side == Side.SERVER && recipient instanceof ServerPlayer serverPlayer
                                ? serverPlayer.connection : clientConnection == null ? recipient : clientConnection;
                        if (identity == null) identity = recipient;
                        final Object capturedIdentity = identity;
                        NetworkPeer peer = new NetworkPeer(side, identity, () -> {
                            var current = player.get();
                            return current != null && (side != Side.SERVER || ((ServerPlayer) current).connection == capturedIdentity);
                        }, bytes -> {
                            var current = player.get();
                            if (current == null) throw new IllegalStateException("Play connection closed");
                            if (side == Side.SERVER) sendRawToPlayer((ServerPlayer) current, bytes);
                            else {
                                var binding = requireTransport().serverConnection();
                                if (binding.identity() != capturedIdentity || !binding.connected().getAsBoolean())
                                    throw new IllegalStateException("Client play connection changed");
                                sendRawToServer(bytes);
                            }
                        });
                        assembled = NetworkFragmentation.accept(frame, peer);
                        if (assembled == null) return;
                        if (AsyncEnvelope.is(assembled)) {
                            AsyncRequestRuntime.receive(assembled, peer, recipient, executor, reject);
                            return;
                        }
                    } catch (RuntimeException failure) {
                        SDMAdvancedLibrary.LOGGER.warn(
                                "Rejected SDMAL packet on {}: {}", side, failure.getMessage());
                        reject.accept("Invalid or incompatible SDMAL packet");
                        return;
                    }
                    Packet packet;
                    try {
                        packet = packets.decode(assembled, side);
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
