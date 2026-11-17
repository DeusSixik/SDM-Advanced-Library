package dev.sixik.sdmal.network;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.core.network.ClientNetwork;
import dev.sixik.sdmal.core.network.NetworkRuntime;
//? if >=1.21 {
/*import dev.sixik.sdmal.core.network.NetworkPayload;
*///?} else {
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client-only Fabric receiver and sender installation.
 */
public final class FabricClientNetwork {
    /**
     * Prevents utility construction.
     */
    private FabricClientNetwork() {}
    /**
     * Connects Packet.send to the active client play connection.
     */
    public static void initialize() {
        //? if >=1.21 {
        /*ClientPlayNetworking.registerGlobalReceiver(NetworkPayload.TYPE, (payload, context) ->
                ClientNetwork.receive(payload.frame(), context.client().getConnection().getConnection(), context.client()));
        FabricNetwork.clientSender(frame -> {
            if (!ClientPlayNetworking.canSend(NetworkPayload.TYPE)) throw new IllegalStateException("Server has no SDMAL play channel");
            ClientPlayNetworking.send(new NetworkPayload(frame));
        });
        *///?} else {
        ClientPlayNetworking.registerGlobalReceiver(SDMAdvancedLibrary.id("packets"), (client, handler, buffer, response) ->
                ClientNetwork.receive(NetworkRuntime.readFrame(buffer), handler.getConnection(), client));
        FabricNetwork.clientSender(frame -> {
            if (!ClientPlayNetworking.canSend(SDMAdvancedLibrary.id("packets"))) throw new IllegalStateException("Server has no SDMAL play channel");
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                NetworkRuntime.writeFrame(buffer, frame);
                ClientPlayNetworking.send(SDMAdvancedLibrary.id("packets"), buffer);
            } catch (RuntimeException | Error failure) {
                buffer.release();
                throw failure;
            }
        });
        //?}
    }
}
