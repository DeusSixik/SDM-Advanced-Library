package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.annotations.EcsNoThreadSafe;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Measures hosted scheduler overhead against the same standalone ECS workload. Synthetic activity
 * gates isolate dispatch cost; Minecraft chunk/world lookups are excluded.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EcsHostedTickBenchmark {
    /**
     * Object count for one complete simulated world tick.
     */
    @Param({"128", "1024"})
    public int count;

    /**
     * Unhosted reference, all active hosts or half active hosts; identical counter calculation.
     */
    @Param({"plain", "hosted", "hostedHalf"})
    public String mode;

    /**
     * Owning scheduler created on the JMH thread.
     */
    private EcsObjectManager manager;

    /**
     * Observed first active counter prevents dead-code elimination.
     */
    private Counter first;

    /**
     * Builds the object set and validates the scheduler plan before measurement.
     */
    @Setup
    public void setup() {
        manager = new EcsObjectManager();
        for (int i = 0; i < count; i++) {
            Counter counter = new Counter();
            if (mode.equals("plain")) {
                manager.create().attach(counter);
            } else {
                var host = new HostedEcsObject();
                boolean active = !mode.equals("hostedHalf") || (i & 1) == 0;
                host.bind(
                        manager,
                        o -> o.attach(counter),
                        o -> o.setTickCondition(new Activity(active)));
            }
            if (i == 0) first = counter;
        }
        manager.scheduler().validatePlan();
    }

    /**
     * Releases all transient benchmark objects after the trial. No disk serialization is measured.
     */
    @TearDown
    public void cleanup() {
        manager.close();
    }

    /**
     * Runs a complete scheduler tick, including capture/compute/apply and legacy dispatch checks.
     *
     * @return first active component's committed integer state
     */
    @Benchmark
    public int worldTick() {
        manager.tickAll();
        return first.value;
    }

    /**
     * Stable synthetic chunk activation; contains no Minecraft lookup or allocation.
     */
    private record Activity(boolean active) implements BooleanSupplier {
        /**
         * @return frozen synthetic chunk activity
         */
        @Override
        public boolean getAsBoolean() {
            return active;
        }
    }

    /**
     * Reuses a primitive snapshot on the owner thread to isolate gate/dispatch overhead.
     */
    @EcsNoThreadSafe
    private static final class Counter extends Component
            implements SnapshotTickableComponent<int[], int[]> {
        /**
         * Current counter value; overflow is harmless for this dispatch workload.
         */
        int value;

        /**
         * One isolated input/result slot reused after each phase barrier.
         */
        final int[] data = new int[1];

        /**
         * @param context owner context @return copied counter in the reusable slot
         */
        @Override
        public int[] capture(EcsContext context) {
            data[0] = value;
            return data;
        }

        /**
         * @param snapshot independent slot @param context restricted compute context @return
         *     updated slot
         */
        @Override
        public int[] compute(int[] snapshot, EcsContext context) {
            snapshot[0]++;
            return snapshot;
        }

        /**
         * @param result computed slot @param context owner context
         */
        @Override
        public void apply(int[] result, EcsContext context) {
            value = result[0];
        }
    }
}
