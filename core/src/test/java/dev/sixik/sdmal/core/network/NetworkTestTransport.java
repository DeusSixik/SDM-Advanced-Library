package dev.sixik.sdmal.core.network;

import net.minecraft.server.level.ServerPlayer;

/** Shares one installed runtime while keeping transport recordings local to each test class. */
final class NetworkTestTransport {
    /** Current test's native connection recorder. */
    private static NetworkRuntime.Transport delegate;
    /** Whether the production runtime has been initialized in this test JVM. */
    private static boolean installed;

    /** Prevents utility construction. */
    private NetworkTestTransport() {}

    /** @param transport recorder to bind before invoking the public network API */
    static synchronized void bind(NetworkRuntime.Transport transport) {
        delegate = transport;
        if (installed) return;
        NetworkRuntime.install(new NetworkRuntime.Transport() {
            /** @param frame outbound native frame */
            @Override public void toServer(byte[] frame) { delegate.toServer(frame); }
            /** @param player recipient @param frame outbound native frame */
            @Override public void toPlayer(ServerPlayer player, byte[] frame) { delegate.toPlayer(player, frame); }
            /** @return test's exact client connection */
            @Override public NetworkRuntime.ClientConnection serverConnection() { return delegate.serverConnection(); }
        });
        installed = true;
    }
}
