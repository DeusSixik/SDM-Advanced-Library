package dev.sixik.sdmal.benchmark;

import dev.sixik.sdmal.core.network.NetworkRuntime;
import dev.sixik.sdmal.example.network.GeneratorStatusPacket;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import org.mockito.Mockito;
import org.openjdk.jmh.annotations.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Measures actual Packet send overloads against a loop of individual sends, excluding native I/O.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PacketBroadcastBenchmark {
    /**
     * Number of recipient entries per broadcast operation, including duplicate test recipients.
     */
    @Param({"1", "32"})
    public int recipientCount;

    /**
     * Preallocated recipient array; mock players are used only as opaque transport identities.
     */
    private Player[] players;

    /**
     * Preallocated random-access view of the same recipients.
     */
    private Collection<? extends Player> collection;

    /**
     * Existing automatically indexed S2C example, avoiding benchmark classes in the mod registry.
     */
    private GeneratorStatusPacket packet;

    /**
     * Observable transport sink; every delivery stores a reference without retaining past frames.
     */
    private volatile byte[] delivered;

    /**
     * Initializes registry and recipient identities outside measured code; freezes the packet
     * codec.
     */
    @Setup
    public void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        players = new Player[recipientCount];
        Arrays.fill(players, Mockito.mock(ServerPlayer.class));
        collection = Arrays.asList(players);
        packet = new GeneratorStatusPacket(BlockPos.ZERO, 1234, 10000);
        NetworkRuntime.install(
                new NetworkRuntime.Transport() {
                    /**
                     * @param frame unexpected C2S frame
                     */
                    @Override
                    public void toServer(byte[] frame) {
                        throw new AssertionError("Unexpected C2S send");
                    }

                    /**
                     * @param player opaque receiving identity
                     * @param frame shared immutable logical frame
                     */
                    @Override
                    public void toPlayer(ServerPlayer player, byte[] frame) {
                        delivered = frame;
                    }
                });
        packet.send(players);
    }

    /**
     * @return final delivered frame from one encode shared across the array
     */
    @Benchmark
    public byte[] arrayBroadcast() {
        packet.send(players);
        return delivered;
    }

    /**
     * @return final delivered frame from one encode shared across the collection
     */
    @Benchmark
    public byte[] collectionBroadcast() {
        packet.send(collection);
        return delivered;
    }

    /**
     * @return final delivered frame after encoding separately for every recipient
     */
    @Benchmark
    public byte[] repeatedSingleSend() {
        for (int index = 0; index < players.length; index++) packet.send(players[index]);
        return delivered;
    }
}
