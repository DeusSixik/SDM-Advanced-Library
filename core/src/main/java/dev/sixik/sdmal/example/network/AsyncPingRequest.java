package dev.sixik.sdmal.example.network;

import dev.sixik.sdmal.api.network.AsyncRequest;
import dev.sixik.sdmal.api.network.AsyncResponder;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkRequest;

/**
 * Minimal typed request/response example used to demonstrate waiting for a result.
 */
@NetworkRequest(id = "sdmal:async_ping", side = Side.SERVER, response = AsyncPingResponse.class, timeoutMillis = 30_000L)
public record AsyncPingRequest(long nonce) implements AsyncRequest<AsyncPingResponse> {
    /**
     * @param context authenticated server context @param responder response handle
     */
    @Override
    public void handle(PacketContext context, AsyncResponder<AsyncPingResponse> responder) {
        responder.complete(new AsyncPingResponse(nonce));
    }
}
