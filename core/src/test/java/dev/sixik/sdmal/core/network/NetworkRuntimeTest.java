package dev.sixik.sdmal.core.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.example.network.GeneratorStatusPacket;
import dev.sixik.sdmal.example.network.RequestGeneratorStatusPacket;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

/**
 * Exercises the public send and reply API with real discovered packet metadata.
 */
class NetworkRuntimeTest {
    /**
     * Captured client-to-server frames, reset before every test.
     */
    private static final ArrayList<byte[]> SERVER_FRAMES = new ArrayList<>();

    /**
     * Captured server-to-client frames; repeated references expose snapshot sharing.
     */
    private static final ArrayList<byte[]> CLIENT_FRAMES = new ArrayList<>();

    /**
     * Actual native recipient order.
     */
    private static final ArrayList<ServerPlayer> RECIPIENTS = new ArrayList<>();

    /**
     * Installs the actual runtime once with a recording native transport.
     */
    @BeforeAll
    static void initialize() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NetworkTestTransport.bind(
                new NetworkRuntime.Transport() {
                    /**
                     * @param frame encoded serverbound snapshot
                     */
                    @Override
                    public void toServer(byte[] frame) {
                        SERVER_FRAMES.add(frame);
                    }

                    /**
                     * @param player native recipient
                     * @param frame encoded clientbound snapshot
                     */
                    @Override
                    public void toPlayer(ServerPlayer player, byte[] frame) {
                        RECIPIENTS.add(player);
                        CLIENT_FRAMES.add(frame);
                    }
                });
    }

    /**
     * Keeps independent tests from observing earlier native sends.
     */
    @BeforeEach
    void clearTransport() {
        SERVER_FRAMES.clear();
        CLIENT_FRAMES.clear();
        RECIPIENTS.clear();
    }

    /**
     * Verifies both directions, connection targets, discovery and direction guards.
     */
    @Test
    void sendAndReply_discoveredExamples_routesSnapshotsToNativeTransport() {
        var registry = PacketRegistry.discover(getClass().getClassLoader());
        var request = new RequestGeneratorStatusPacket(new BlockPos(3, 5, 7));
        var response = new GeneratorStatusPacket(BlockPos.ZERO, 41, 100);
        var player = mock(ServerPlayer.class);
        request.send();
        response.send(player);
        assertEquals(request, registry.decode(SERVER_FRAMES.get(0), Side.SERVER));
        assertEquals(response, registry.decode(CLIENT_FRAMES.get(0), Side.CLIENT));
        assertSame(player, RECIPIENTS.get(0));
        new PacketContext(Side.SERVER, player, Runnable::run).reply(response);
        new PacketContext(Side.CLIENT, mock(Player.class), Runnable::run).reply(request);
        assertEquals(2, SERVER_FRAMES.size());
        assertEquals(2, CLIENT_FRAMES.size());
        assertThrows(IllegalStateException.class, response::send);
        assertThrows(IllegalStateException.class, () -> request.send(player));
        assertThrows(
                IllegalStateException.class,
                () -> new PacketContext(Side.SERVER, player, Runnable::run).reply(request));
        assertThrows(IllegalStateException.class, () -> NetworkRuntime.install(null));
    }

    /**
     * Every group overload delivers one shared encoded snapshot to all selected recipients.
     *
     * @param group recipient container or server broadcast overload
     */
    @ParameterizedTest
    @ValueSource(strings = {"array", "arrayList", "linkedList", "linkedSet", "server"})
    void sendGroup_validPlayers_sharesOneFrameAndPreservesRecipients(String group) {
        var first = mock(ServerPlayer.class);
        var second = mock(ServerPlayer.class);
        var packet = new GeneratorStatusPacket(BlockPos.ZERO, 41, 100);
        sendToGroup(packet, group, first, second);
        assertEquals(List.of(first, second), RECIPIENTS);
        assertEquals(2, CLIENT_FRAMES.size());
        assertSame(CLIENT_FRAMES.get(0), CLIENT_FRAMES.get(1));
        var registry = PacketRegistry.discover(getClass().getClassLoader());
        assertEquals(packet, registry.decode(CLIENT_FRAMES.get(0), Side.CLIENT));
        assertTrue(SERVER_FRAMES.isEmpty());
    }

    /**
     * All recipients are checked before even a valid prefix of the group can be sent.
     *
     * @param group supported recipient container
     */
    @ParameterizedTest
    @ValueSource(strings = {"array", "arrayList", "linkedList", "linkedSet"})
    void sendGroup_invalidRecipient_rejectsWholeGroupBeforeDelivery(String group) {
        var first = mock(ServerPlayer.class);
        var packet = new GeneratorStatusPacket(BlockPos.ZERO, 41, 100);
        assertThrows(
                IllegalArgumentException.class,
                () -> sendToGroup(packet, group, first, mock(Player.class)));
        assertThrows(IllegalArgumentException.class, () -> sendToGroup(packet, group, first, null));
        assertTrue(RECIPIENTS.isEmpty());
        assertTrue(CLIENT_FRAMES.isEmpty());
    }

    /**
     * Empty groups avoid payload encoding but still reject C2S messages and missing registrations.
     *
     * @param group recipient container or server broadcast overload
     */
    @ParameterizedTest
    @ValueSource(strings = {"array", "arrayList", "linkedList", "linkedSet", "server"})
    void sendGroup_empty_validatesDirectionWithoutEncoding(String group) {
        sendToGroup(new GeneratorStatusPacket(BlockPos.ZERO, 41, 100), group);
        assertThrows(
                IllegalStateException.class,
                () -> sendToGroup(new RequestGeneratorStatusPacket(BlockPos.ZERO), group));
        assertThrows(IllegalStateException.class, () -> sendToGroup(context -> {}, group));
        assertTrue(RECIPIENTS.isEmpty());
        assertTrue(CLIENT_FRAMES.isEmpty());
    }

    /**
     * A group overload never bypasses the annotation's receiving direction.
     *
     * @param group recipient container or server broadcast overload
     */
    @ParameterizedTest
    @ValueSource(strings = {"array", "arrayList", "linkedList", "linkedSet", "server"})
    void sendGroup_serverboundPacket_rejectsWithoutDelivery(String group) {
        assertThrows(
                IllegalStateException.class,
                () ->
                        sendToGroup(
                                new RequestGeneratorStatusPacket(BlockPos.ZERO),
                                group,
                                mock(ServerPlayer.class)));
        assertTrue(RECIPIENTS.isEmpty());
    }

    /**
     * Explicit repeated entries preserve the caller's chosen delivery multiplicity.
     *
     * @param group ordered recipient container
     */
    @ParameterizedTest
    @ValueSource(strings = {"array", "arrayList", "linkedList"})
    void sendGroup_duplicateRecipient_deliversForEachEntry(String group) {
        var player = mock(ServerPlayer.class);
        sendToGroup(new GeneratorStatusPacket(BlockPos.ZERO, 41, 100), group, player, player);
        assertEquals(List.of(player, player), RECIPIENTS);
        assertSame(CLIENT_FRAMES.get(0), CLIENT_FRAMES.get(1));
    }

    /**
     * Null containers fail before the native transport is invoked.
     */
    @Test
    void sendGroup_nullContainer_rejectsWithoutDelivery() {
        var packet = new GeneratorStatusPacket(BlockPos.ZERO, 41, 100);
        assertThrows(NullPointerException.class, () -> packet.send((Player[]) null));
        assertThrows(NullPointerException.class, () -> packet.send((Collection<Player>) null));
        assertThrows(NullPointerException.class, () -> packet.send((MinecraftServer) null));
        assertTrue(RECIPIENTS.isEmpty());
    }

    /**
     * Invokes the requested public group overload with independently constructed recipient containers.
     *
     * @param packet message under test
     * @param group overload selected by the test
     * @param players ordered fixture recipients
     */
    private static void sendToGroup(Packet packet, String group, Player... players) {
        switch (group) {
            case "array" -> packet.send(players);
            case "arrayList" -> packet.send(new ArrayList<>(Arrays.asList(players)));
            case "linkedList" -> packet.send(new LinkedList<>(Arrays.asList(players)));
            case "linkedSet" -> packet.send(new LinkedHashSet<>(Arrays.asList(players)));
            case "server" -> {
                var server = mock(MinecraftServer.class);
                var playerList = mock(PlayerList.class);
                List<ServerPlayer> connected =
                        Arrays.stream(players).map(ServerPlayer.class::cast).toList();
                when(server.getPlayerList()).thenReturn(playerList);
                when(playerList.getPlayers()).thenReturn(connected);
                packet.send(server);
            }
            default -> throw new AssertionError("Unknown group: " + group);
        }
    }
}
