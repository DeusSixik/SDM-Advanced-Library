package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.sync.Field;
import dev.sixik.sdmal.api.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.api.ecs.sync.SyncedWorldObjectComponent.DeltaTracker;
import dev.sixik.sdmal.api.ecs.sync.SyncedWorldObjectComponent;
import dev.sixik.sdmal.api.ecs.sync.TrackedField;
import dev.sixik.sdmal.impl.ecs.WorldObjectTicker;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.openjdk.jmh.annotations.*;

/**
 * Measures ECS tick dispatch and correctness-preserving field snapshot serialization.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EcsBenchmark {
    /**
     * Manager containing 128 objects with one tickable component each.
     */
    private WorldObjectManager manager;
    /**
     * Independent object with eight tickable components.
     */
    private WorldObject object;
    /**
     * Revisioned field used by the snapshot measurement.
     */
    private TrackedField<Integer> field;
    /**
     * Component used by the delta serialization measurement.
     */
    private Counter synced;
    /**
     * Sender state advanced after each encoded delta.
     */
    private DeltaTracker tracker;
    /**
     * Reused buffer to exclude buffer construction from delta measurements.
     */
    private FriendlyByteBuf buffer;
    /**
     * Tick sequence used by the object benchmark.
     */
    private long tick;
    /** Previous map-iteration dispatch path for comparison with stable active arrays. */
    private Map<UUID, WorldObject> legacyObjects;
    /** Independent sequence for the previous dispatch path. */
    private long legacyTick;
    /** Mutable declaration list representing the previous per-call schema copying input. */
    private List<Field<?>> declarations;

    /**
     * Prepares all benchmark inputs outside measured operations.
     */
    @Setup(Level.Trial)
    public void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        manager = new WorldObjectManager();
        for (int index = 0; index < 128; index++) manager.create().attach(new PlainCounter());
        object = new WorldObject();
        for (int index = 0; index < 8; index++) object.attach(new PlainCounter());
        field = new TrackedField<>("value", FieldCodecs.intValue(), 42);
        synced = new Counter();
        tracker = new DeltaTracker();
        buffer = new FriendlyByteBuf(Unpooled.buffer(256));
        legacyObjects = new ConcurrentHashMap<>();
        for (WorldObject entry : manager.snapshot()) legacyObjects.put(entry.id(), entry);
        declarations = new ArrayList<>(synced.syncedFields());
    }

    /**
     * Releases reference-counted and component resources after the trial.
     */
    @TearDown(Level.Trial)
    public void cleanup() {
        buffer.release();
        manager.close();
        object.detach();
    }

    /**
     * Ticks eight components using the object's stable snapshot.
     *
     * @return number of attached components
     */
    @Benchmark
    public int tickObject() {
        object.tick(++tick);
        return object.size();
    }

    /**
     * Dispatches a manager tick across 128 objects.
     *
     * @return last dispatched tick number
     */
    @Benchmark
    public long tickManager() {
        manager.tickAll();
        return manager.lastTick();
    }

    /**
     * Measures the previous weakly consistent map traversal on the same component workload.
     * @return last sequence issued to every object
     */
    @Benchmark
    public long legacyTickManager() {
        long current = ++legacyTick;
        for (WorldObject entry : legacyObjects.values()) entry.tick(current);
        return current;
    }

    /**
     * Measures the previous immutable schema copy performed on every schema lookup.
     * @return newly copied field declarations, observed by JMH
     */
    @Benchmark
    public List<Field<?>> uncachedFieldSchema() { return List.copyOf(declarations); }

    /**
     * Measures reuse of a frozen schema on repeated lookups.
     * @return shared immutable declarations, observed by JMH
     */
    @Benchmark
    public List<Field<?>> cachedFieldSchema() { return synced.syncedFields(); }

    /**
     * Dispatches the same 128-object workload through the world integration helper.
     * @return last dispatched manager sequence, observed by JMH
     */
    @Benchmark
    public long tickWorld() {
        WorldObjectTicker.tick(manager, true);
        return manager.lastTick();
    }

    /**
     * Measures a frozen world without dispatching components.
     * @return unchanged manager sequence, observed by JMH
     */
    @Benchmark
    public long tickFrozenWorld() {
        WorldObjectTicker.tick(manager, false);
        return manager.lastTick();
    }

    /**
     * Captures a field's matching value and revision pair.
     *
     * @return snapshot exposed to JMH to prevent elimination
     */
    @Benchmark
    public TrackedField.Snapshot<Integer> snapshotField() {
        return field.snapshot();
    }

    /**
     * Encodes one changed field into a reused packet buffer.
     *
     * @return encoded byte count, observed by JMH
     */
    @Benchmark
    public int writeDelta() {
        synced.value.update(value -> value == 42 ? 43 : 42);
        buffer.clear();
        synced.writeDelta(buffer, tracker);
        return buffer.writerIndex();
    }

    /**
     * Tick-only component avoiding field serialization in the dispatch benchmarks.
     */
    private static class PlainCounter extends AbstractWorldObjectComponent implements TickableComponent {
        /**
         * Last observed manager tick number, initially zero.
         */
        long lastTick;

        /**
         * Records the tick. @param tick manager tick number
         */
        @Override
        public void onTick(long tick) {
            lastTick = tick;
        }
    }

    /**
     * One-field component used by the delta benchmark.
     */
    private static class Counter extends SyncedWorldObjectComponent {
        /**
         * Revisioned bounded counter alternating between 42 and 43.
         */
        final TrackedField<Integer> value = tracked("value", FieldCodecs.intValue(), 42);
    }
}
