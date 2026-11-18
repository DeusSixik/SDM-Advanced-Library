package dev.sixik.sdmal.benchmark;

import dev.sixik.sdmal.api.recipes.RecipeEntry;
import dev.sixik.sdmal.api.recipes.RecipeLookup;
import dev.sixik.sdmal.api.recipes.RecipeManager;
import dev.sixik.sdmal.api.recipes.RecipeType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Compares identical immutable data through a locked scan, unlocked scan, and primitive candidate
 * index. Inputs and recipes are allocated before measurement. Four readers share one generation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RecipeBenchmark {
    /**
     * Category size, covering small and larger machine recipe sets.
     */
    @Param({"256", "4096"})
    public int recipes;
    /**
     * Monitor shared by the traditional serialized scan baseline.
     */
    private final Object lock = new Object();
    /**
     * Read-only linear reference, sorted by recipe id.
     */
    private RecipeLookup<Input, Data> linear;
    /**
     * Captured primitive-indexed lookup.
     */
    private RecipeLookup<Input, Data> indexed;
    /**
     * Manager convenience API performs one volatile snapshot capture per query.
     */
    private RecipeManager manager;
    /**
     * Shared immutable indexed category token.
     */
    private RecipeType<Input, Data> indexedType;
    /**
     * Immutable last-entry hit, allocated outside benchmark methods.
     */
    private Input input;

    /**
     * Builds equivalent immutable datasets and validates the same last-entry result.
     */
    @Setup
    public void setup() {
        var linearType = RecipeType.<Input, Data>of("bench:linear", (r, i) -> r.item() == i.item());
        indexedType = RecipeType.indexed("bench:indexed", (r, i) -> r.item() == i.item(),
                Input::item, r -> new int[] {r.item()});
        var linearEntries = new ObjectArrayList<RecipeEntry<?>>();
        var indexedEntries = new ObjectArrayList<RecipeEntry<?>>();
        for (int index = 0; index < recipes; index++) {
            var data = new Data(index);
            String id = "bench:r" + String.format(java.util.Locale.ROOT, "%05d", index);
            linearEntries.add(new RecipeEntry<>(id, linearType, data));
            indexedEntries.add(new RecipeEntry<>(id, indexedType, data));
        }
        var linearManager = new RecipeManager();
        linear = linearManager.replaceAll(linearEntries).lookup(linearType);
        manager = new RecipeManager();
        indexed = manager.replaceAll(indexedEntries).lookup(indexedType);
        input = new Input(recipes - 1);
        if (!linear.first(input).id().equals(indexed.first(input).id())) {
            throw new IllegalStateException("Benchmark workloads must find the same recipe");
        }
    }

    /**
     * @return reference last-entry match under one shared reader lock
     */
    @Benchmark
    public RecipeEntry<Data> synchronizedLinear() {
        synchronized (lock) {
            return linear.first(input);
        }
    }

    /**
     * @return equivalent lock-free linear category match
     */
    @Benchmark
    public RecipeEntry<Data> snapshotLinear() { return linear.first(input); }

    /**
     * @return primitive-indexed match using an already captured generation
     */
    @Benchmark
    public RecipeEntry<Data> snapshotIndexed() { return indexed.first(input); }

    /**
     * @return primitive-indexed match including one volatile snapshot read
     */
    @Benchmark
    public RecipeEntry<Data> managerIndexed() { return manager.first(indexedType, input); }

    /**
     * @param item immutable detached native-like item id
     */
    public record Input(int item) {}
    /**
     * @param item immutable accepted item id
     */
    public record Data(int item) {}
}
