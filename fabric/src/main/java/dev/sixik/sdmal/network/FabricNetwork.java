package dev.sixik.sdmal.network;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.core.network.NetworkRuntime;
//? if >=1.21 {
/*import dev.sixik.sdmal.core.network.NetworkPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
*///?} else {
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
//?}
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric native play transport for automatically indexed messages.
 */
public final class FabricNetwork implements NetworkRuntime.Transport {
    /**
     * Client sender installed only by the client initializer.
     */
    private static volatile java.util.function.Consumer<byte[]> clientSender;
    /**
     * Creates the loader transport.
     */
    private FabricNetwork() {}
    /**
     * Installs payload codecs and the server receiver before connections open.
     */
    public static void initialize() {
        NetworkRuntime.install(new FabricNetwork());
        //? if >=1.21 {
        /*PayloadTypeRegistry.playC2S().register(NetworkPayload.TYPE, NetworkPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(NetworkPayload.TYPE, NetworkPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(NetworkPayload.TYPE, (payload, context) ->
                receive(payload.frame(), context.player(), context.server()));
        *///?} else {
        ServerPlayNetworking.registerGlobalReceiver(SDMAdvancedLibrary.id("packets"), (server, player, handler, buffer, response) ->
                receive(NetworkRuntime.readFrame(buffer), player, server));
        //?}
    }
    /**
     * Binds the local client sender without loading client APIs on a server.
     * @param sender client play transport
     */
    static void clientSender(java.util.function.Consumer<byte[]> sender) { clientSender = sender; }
    /**
     * Queues handling on the server and discards disconnected work.
     * @param frame copied bounded frame
     * @param player authenticated sender
     * @param executor server game executor
     */
    private static void receive(byte[] frame, ServerPlayer player, java.util.concurrent.Executor executor) {
        //? if <1.20 {
        /*NetworkRuntime.receive(frame, Side.SERVER, () -> player.connection.connection.isConnected() ? player.connection.player : null,
        *///?} else {
        NetworkRuntime.receive(frame, Side.SERVER, () -> player.connection.isAcceptingMessages() ? player.connection.player : null,
        //?}
                executor, reason -> player.connection.disconnect(Component.literal(reason)));
    }
    /**
     * @param frame encoded SERVER message
     */
    @Override public void toServer(byte[] frame) {
        if (clientSender == null) throw new IllegalStateException("No local Fabric client transport");
        clientSender.accept(frame);
    }
    /**
     * @param player server recipient
     * @param frame encoded CLIENT message
     */
    @Override public void toPlayer(ServerPlayer player, byte[] frame) {
        //? if >=1.21 {
        /*if (!ServerPlayNetworking.canSend(player, NetworkPayload.TYPE)) throw new IllegalStateException("Client has no SDMAL play channel");
        ServerPlayNetworking.send(player, new NetworkPayload(frame));
        *///?} else {
        if (!ServerPlayNetworking.canSend(player, SDMAdvancedLibrary.id("packets"))) throw new IllegalStateException("Client has no SDMAL play channel");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkRuntime.writeFrame(buffer, frame);
            ServerPlayNetworking.send(player, SDMAdvancedLibrary.id("packets"), buffer);
        } catch (RuntimeException | Error failure) {
            buffer.release();
            throw failure;
        }
        //?}
    }
}
