package dev.sixik.sdmal.benchmark;

import dev.sixik.sdmal.api.network.Packet;
import dev.sixik.sdmal.api.network.PacketContext;
import dev.sixik.sdmal.api.network.Side;
import dev.sixik.sdmal.api.network.annotations.NetworkPacket;
import dev.sixik.sdmal.core.network.PacketRegistry;

import io.netty.buffer.Unpooled;

import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;

import org.openjdk.jmh.annotations.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Compares complete automatic frames with an equivalent handwritten native binary format.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PacketBenchmark {
    /**
     * Immutable runtime field plans.
     */
    private PacketRegistry registry;

    /**
     * Prebuilt input message.
     */
    private Sample packet;

    /**
     * Prebuilt input frame.
     */
    private byte[] encoded;

    /**
     * Protocol/id/fingerprint bytes shared with the handwritten baseline.
     */
    private byte[] prefix;

    /**
     * Initializes native formats and validates equality with the handwritten reference.
     */
    @Setup
    public void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registry = new PacketRegistry(List.of(Sample.class));
        packet = new Sample(1234L, 7, "generator");
        encoded = registry.encode(packet, Side.SERVER);
        prefix = Arrays.copyOf(encoded, encoded.length - 8 - 4 - 1 - 1 - packet.name().length());
        if (!Arrays.equals(encoded, manualEncode()) || !packet.equals(manualDecode()))
            throw new IllegalStateException("Reference differs");
    }

    /**
     * @return complete automatically encoded byte snapshot
     */
    @Benchmark
    public byte[] automaticEncode() {
        return registry.encode(packet, Side.SERVER);
    }

    /**
     * @return fully decoded automatic record
     */
    @Benchmark
    public Packet automaticDecode() {
        return registry.decode(encoded, Side.SERVER);
    }

    /**
     * @return identical handwritten frame, including framing and snapshot allocation
     */
    @Benchmark
    public byte[] manualEncode() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer(256, PacketRegistry.MAX_BYTES));
        try {
            buffer.writeBytes(prefix);
            buffer.writeLong(packet.energy());
            buffer.writeInt(packet.mode());
            buffer.writeByte(1);
            buffer.writeUtf(packet.name());
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /**
     * @return handwritten decoded record with equivalent frame checks
     */
    @Benchmark
    public Sample manualDecode() {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        try {
            for (int index = 0; index < prefix.length; index++) {
                if (buffer.readByte() != prefix[index])
                    throw new IllegalStateException("Invalid frame");
            }
            long energy = buffer.readLong();
            int mode = buffer.readInt();
            String name = buffer.readByte() == 1 ? buffer.readUtf() : null;
            if (buffer.isReadable()) throw new IllegalStateException("Trailing bytes");
            return new Sample(energy, mode, name);
        } finally {
            buffer.release();
        }
    }

    /**
     * Representative small gameplay message.
     *
     * @param energy energy units
     * @param mode operating mode
     * @param name display label
     */
    @NetworkPacket(id = "sdmal_benchmark:sample", side = Side.SERVER)
    public record Sample(long energy, int mode, String name) implements Packet {
        /**
         * @param context ignored benchmark context
         */
        @Override
        public void handle(PacketContext context) {}
    }
}
