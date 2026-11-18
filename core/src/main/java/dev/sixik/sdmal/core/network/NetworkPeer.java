package dev.sixik.sdmal.core.network;

import dev.sixik.sdmal.api.network.Side;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One logical endpoint of an authenticated play connection; reconnects have different identities.
 */
final class NetworkPeer {
    /**
     * Receiving side of this local endpoint, distinct in an integrated server.
     */
    final Side side;
    /**
     * Connection/listener identity; UUIDs cannot distinguish reconnects.
     */
    final Object identity;
    /**
     * Live connection check without retaining a decoded packet.
     */
    private final BooleanSupplier connected;
    /**
     * Bound outgoing transport, restricted to this connection.
     */
    private final Consumer<byte[]> sender;

    /**
     * @param side      local receiving side
     * @param identity  stable connection identity
     * @param connected connection-liveness test
     * @param sender    bound outgoing logical frame sender
     */
    NetworkPeer(Side side, Object identity, BooleanSupplier connected, Consumer<byte[]> sender) {
        this.side = Objects.requireNonNull(side);
        this.identity = Objects.requireNonNull(identity, "Play connection is unavailable");
        this.connected = connected;
        this.sender = sender;
    }

    /**
     * @return whether the original connection is still active
     */
    boolean connected() {
        return connected.getAsBoolean();
    }

    /**
     * @param frame owned immutable logical frame
     */
    void send(byte[] frame) {
        if (!connected()) throw new IllegalStateException("Play connection closed");
        sender.accept(frame);
    }

    /**
     * @param other authenticated response endpoint @return whether side and connection match exactly
     */
    boolean matches(NetworkPeer other) {
        return side == other.side && identity == other.identity;
    }
}
