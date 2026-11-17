package dev.sixik.sdmal.api.network;

/**
 * Logical side receiving and executing a packet, including in an integrated server.
 */
public enum Side {
    /**
     * Server-to-client packet.
     */
    CLIENT,
    /**
     * Client-to-server packet.
     */
    SERVER
}
