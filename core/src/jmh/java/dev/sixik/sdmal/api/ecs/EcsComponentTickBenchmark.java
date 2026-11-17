package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.api.ecs.annotations.EcsNoThreadSafe;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Measures automatic component dispatch with reused primitive input/results, isolating scheduler/task overhead. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EcsComponentTickBenchmark {
    /** Number of independent attached components. */
    @Param({"128", "1024"}) public int count;
    /** Owner-only reference or automatic worker chunks, with identical component calculations. */
    @Param({"owner", "worker"}) public String execution;
    /** World manager ticked by the benchmark harness thread. */
    private WorldObjectManager manager;
    /** Observed component prevents elimination of the component update. */
    private Counter first;

    /** Creates attachments and caches the automatic plan before measured dispatch. */
    @Setup public void setup() {
        manager = new WorldObjectManager();
        for (int i = 0; i < count; i++) {
            Counter counter = execution.equals("owner") ? new OwnerCounter() : new Counter();
            manager.create().attach(counter);
            if (i == 0) first = counter;
        }
        manager.scheduler().validatePlan();
    }

    /** Releases command buffers, attachments and reusable per-component data after the trial. */
    @TearDown public void cleanup() { manager.close(); }

    /**
     * Dispatches a complete world tick with trivial arithmetic in every automatic component.
     * @return committed first-component state observed by JMH
     */
    @Benchmark public int automaticComponentTick() { manager.tickAll(); return first.value; }

    /** Reusable input/result owned by one component for the duration of its phase barrier. */
    private static final class Data {
        /** Copied input counter, in arbitrary integer units. */
        int input;
        /** Computed next counter, applied only after the worker barrier. */
        int output;
    }

    /** Minimal component that performs no snapshot allocations inside its compute callback. */
    private static class Counter extends AbstractWorldObjectComponent implements SnapshotTickableComponent<Data, Data> {
        /** Owner-thread counter. */
        int value;
        /** Exclusive reusable snapshot/result; never shared across component instances. */
        private final Data data = new Data();
        /** @param context owner context @return exclusively owned copied input */
        @Override public Data capture(WorldContext context) { data.input = value; return data; }
        /** @param input copied data @param context restricted commands @return updated independent data */
        @Override public Data compute(Data input, WorldContext context) { input.output = input.input + 1; return input; }
        /** @param result computed data @param context owner context */
        @Override public void apply(Data result, WorldContext context) { value = result.output; }
    }

    /** Same arithmetic with worker execution prohibited, for measuring dispatch/task overhead. */
    @EcsNoThreadSafe private static final class OwnerCounter extends Counter {}
}
