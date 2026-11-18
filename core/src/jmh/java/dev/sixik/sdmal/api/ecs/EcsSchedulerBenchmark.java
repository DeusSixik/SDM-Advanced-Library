package dev.sixik.sdmal.api.ecs;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Measures phase scheduling overhead and allocation behavior on a small independent system graph. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EcsSchedulerBenchmark {
    /** Standalone manager with a cached scheduler plan. */
    private EcsObjectManager manager;

    /** Registers independent systems before timing begins. */
    @Setup
    public void setup() {
        manager = new EcsObjectManager();
        for (int index = 0; index < 16; index++) {
            int id = index;
            manager.scheduler().register(new EcsSystem<Long, Long>() {
                @Override public String id() { return "benchmark:system_" + id; }
                @Override public EcsAccess access() { return EcsAccess.NONE; }
                @Override public boolean parallel() { return false; }
                @Override public Long prepare(EcsContext context, EcsObjectManager owner) { return context.tick(); }
                @Override public Long compute(Long input, EcsContext context) { return input + 1; }
                @Override public void apply(Long result, EcsContext context, EcsObjectManager owner) {}
            });
        }
        manager.scheduler().validatePlan();
    }

    /** Releases manager buffers after the benchmark trial. */
    @TearDown
    public void cleanup() { manager.close(); }

    /**
     * Measures a complete PRE/NORMAL/POST scheduler pass with no world allocations.
     * @return manager tick sequence observed by the harness
     */
    @Benchmark
    public long tickScheduler() {
        manager.tickAll();
        return manager.lastTick();
    }
}
