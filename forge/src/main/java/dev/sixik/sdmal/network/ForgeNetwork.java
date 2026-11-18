package dev.sixik.sdmal.network;

import dev.sixik.sdmal.SDMAdvancedLibrary;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.core.network.ClientNetwork;
import dev.sixik.sdmal.core.network.NetworkRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
//? if >=1.21 {
/*import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.event.network.CustomPayloadEvent;
*///?} else {
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkEvent;
//?}

/**
 * Forge native envelope transport, registered once during common setup.
 */
public final class ForgeNetwork implements NetworkRuntime.Transport {
    /** @return local client connection; unavailable in a dedicated-server process */
    @Override public NetworkRuntime.ClientConnection serverConnection() {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient())
            throw new IllegalStateException("No local Forge client connection");
        return ClientNetwork.serverConnection();
    }
    /**
     * Native channel; logical ids are carried inside each frame.
     */
    private static SimpleChannel channel;
    /**
     * Creates the loader transport.
     */
    private ForgeNetwork() {}
    /**
     * Installs the registry and one bidirectional play envelope.
     */
    public static void initialize() {
        NetworkRuntime.install(new ForgeNetwork());
        //? if >=1.21 {
        /*channel = ChannelBuilder.named(SDMAdvancedLibrary.id("packets")).networkProtocolVersion(1).simpleChannel();
        channel.messageBuilder(Frame.class, 0)
                .encoder((frame, buffer) -> NetworkRuntime.writeFrame(buffer, frame.bytes()))
                .decoder(buffer -> new Frame(NetworkRuntime.readFrame(buffer)))
                .consumerNetworkThread((frame, context) -> { receive(frame, context); }).add();
        channel.build();
        *///?} else {
        channel = NetworkRegistry.newSimpleChannel(SDMAdvancedLibrary.id("packets"), () -> "1", "1"::equals, "1"::equals);
        channel.messageBuilder(Frame.class, 0)
                .encoder((frame, buffer) -> NetworkRuntime.writeFrame(buffer, frame.bytes()))
                .decoder(buffer -> new Frame(NetworkRuntime.readFrame(buffer)))
                .consumerNetworkThread((frame, context) -> { receive(frame, context.get()); }).add();
        //?}
    }
    /**
     * Dispatches using the native direction and executor.
     * @param frame decoded envelope
     * @param context authoritative connection context
     */
    //? if >=1.21 {
    /*private static void receive(Frame frame, CustomPayloadEvent.Context context) {
        var connection = context.getConnection();
        boolean server = context.isServerSide();
    *///?} else {
    private static void receive(Frame frame, NetworkEvent.Context context) {
        var connection = context.getNetworkManager();
        boolean server = context.getDirection().getReceptionSide().isServer();
    //?}
        if (server) {
            NetworkRuntime.receive(frame.bytes(), Side.SERVER, () -> connection.isConnected() ? context.getSender() : null,
                    context::enqueueWork, reason -> connection.disconnect(Component.literal(reason)));
        } else {
            ClientNetwork.receive(frame.bytes(), connection, context::enqueueWork);
        }
        context.setPacketHandled(true);
    }
    /**
     * @param frame encoded SERVER message
     */
    @Override public void toServer(byte[] frame) {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient())
            throw new IllegalStateException("No local Forge client transport");
        //? if >=1.21 {
        /*channel.send(new Frame(frame), PacketDistributor.SERVER.noArg());
        *///?} else {
        channel.sendToServer(new Frame(frame));
        //?}
    }
    /**
     * @param player server recipient
     * @param frame encoded CLIENT message
     */
    @Override public void toPlayer(ServerPlayer player, byte[] frame) {
        //? if >=1.21 {
        /*channel.send(new Frame(frame), PacketDistributor.PLAYER.with(player));
        *///?} else {
        channel.send(PacketDistributor.PLAYER.with(() -> player), new Frame(frame));
        //?}
    }
    /** Copied bytes survive the native buffer lifetime.
     * @param bytes bounded logical frame
     */
    private record Frame(byte[] bytes) {}
}
