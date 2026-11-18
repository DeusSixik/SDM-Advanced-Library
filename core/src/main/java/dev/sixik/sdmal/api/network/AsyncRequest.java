package dev.sixik.sdmal.api.network;

import dev.sixik.sdmal.core.network.AsyncRequestRuntime;

import java.util.concurrent.CompletableFuture;

import net.minecraft.world.entity.player.Player;

/**
 * Typed request whose response is delivered asynchronously over the same annotated transport.
 * The request class must carry {@code @NetworkRequest}; its response type is declared there.
 *
 * @param <R> response value type, or {@link Void} for an acknowledgement without result data
 */
public interface AsyncRequest<R> {
    /**
     * Handles a request on the receiving game thread and completes its response exactly once.
     *
     * @param context   authenticated receiving connection
     * @param responder response completion handle
     */
    void handle(PacketContext context, AsyncResponder<R> responder);

    /**
     * Sends a request from the client to the server.
     * Do not block the game thread with {@code join()} or {@code get()}: it also receives the reply.
     * Cancellation releases the local pending request; it does not cancel the remote handler.
     *
     * @return future completed on the game executor for a response, or by maintenance for timeout/disconnect
     * @throws IllegalStateException if unindexed, sent in the wrong direction, or no transport is installed
     */
    default CompletableFuture<R> send() {
        return AsyncRequestRuntime.sendToServer(this);
    }

    /**
     * Sends a request from the server to one client.
     *
     * @param player authenticated client recipient
     * @return future for this exact connection; timeout/disconnect can complete it outside the game thread
     * @throws IllegalArgumentException if the recipient is not a server player
     * @throws IllegalStateException    if unindexed, sent in the wrong direction, or no transport is installed
     */
    default CompletableFuture<R> send(Player player) {
        return AsyncRequestRuntime.sendToPlayer(this, player);
    }
}
