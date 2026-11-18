package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.annotations.EcsData;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.openjdk.jmh.annotations.*;

/** Measures native annotated component snapshots against an explicit NBT-in-buffer reference on equal values. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EcsNetworkBenchmark {
    /** Source component, initialized once per trial. */
    private Energy source;
    /** Target component receiving decoded snapshots. */
    private Energy target;
    /** Reused output buffer, excluding buffer construction from measurements. */
    private FriendlyByteBuf output;
    /** Immutable native frame prepared before read measurements. */
    private FriendlyByteBuf nativeInput;
    /** Immutable NBT reference frame prepared before read measurements. */
    private FriendlyByteBuf nbtInput;

    /** Initializes real Minecraft types and warms schema construction outside measured operations. */
    @Setup(Level.Trial) public void setup() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        source = new Energy(); target = new Energy();
        output = new FriendlyByteBuf(Unpooled.buffer(1024));
        nativeInput = new FriendlyByteBuf(Unpooled.buffer(1024));
        nbtInput = new FriendlyByteBuf(Unpooled.buffer(1024));
        source.writeNetwork(nativeInput);
        CompoundTag nbt = new CompoundTag(); source.writeNbt(nbt); nbtInput.writeNbt(nbt);
        target.readNetwork(nativeInput); nativeInput.readerIndex(0);
        target.readNbt(nbtInput.readNbt()); nbtInput.readerIndex(0);
    }

    /** Releases all caller-owned reference-counted buffers. */
    @TearDown(Level.Trial) public void close() { output.release(); nativeInput.release(); nbtInput.release(); }

    /** @return bytes written directly, including the length/version/schema compatibility header */
    @Benchmark public int writeNative() { output.clear(); source.writeNetwork(output); return output.writerIndex(); }

    /** @return bytes written by the reference route which serializes the same persisted values to NBT first */
    @Benchmark public int writeViaNbt() {
        output.clear(); CompoundTag nbt = new CompoundTag(); source.writeNbt(nbt); output.writeNbt(nbt);
        return output.writerIndex();
    }

    /** @return decoded energy, observed to prevent removal of native reads */
    @Benchmark public int readNative() { nativeInput.readerIndex(0); target.readNetwork(nativeInput); return target.energy; }

    /** @return decoded energy from the NBT-in-buffer reference route */
    @Benchmark public int readViaNbt() { nbtInput.readerIndex(0); target.readNbt(nbtInput.readNbt()); return target.energy; }

    /** Fixed data workload containing primitives, a UUID, text and a validated record. */
    public static final class Energy extends Component {
        /** Current energy in arbitrary units. */
        @EcsPersist("energy") @EcsSync private int energy = 357;
        /** Stable sample ownership identity. */
        @EcsPersist("owner") @EcsSync private UUID owner = new UUID(11, 29);
        /** Sample display label. */
        @EcsPersist("label") @EcsSync private String label = "Battery";
        /** Two-integer custom data record. */
        @EcsPersist("battery") @EcsSync private Battery battery = new Battery(42, 100);
    }

    /**
     * Structured record encoded identically in meaning by both reference routes.
     * @param stored stored energy units
     * @param capacity maximum energy units
     */
    @EcsData public record Battery(@EcsPersist("stored") int stored, @EcsPersist("capacity") int capacity) { }
}
