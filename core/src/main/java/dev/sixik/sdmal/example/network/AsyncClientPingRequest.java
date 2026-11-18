package dev.sixik.sdmal.example.network;

import dev.sixik.sdmal.api.network.AsyncRequest;
import dev.sixik.sdmal.api.network.AsyncResponder;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkRequest;

/**
 * Server-to-client request example: send(player) returns the client's correlated result.
 */
@NetworkRequest(id = "sdmal:async_client_ping", side = Side.CLIENT, response = AsyncPingResponse.class)
public record AsyncClientPingRequest(long nonce) implements AsyncRequest<AsyncPingResponse> {
    /**
     * @param context local client context @param responder connection-bound response
     */
    @Override
    public void handle(PacketContext context, AsyncResponder<AsyncPingResponse> responder) {
        responder.complete(new AsyncPingResponse(nonce));
    }
}
