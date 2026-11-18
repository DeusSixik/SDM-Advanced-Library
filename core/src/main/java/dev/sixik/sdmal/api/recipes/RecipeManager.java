package dev.sixik.sdmal.api.recipes;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import java.util.Collection;
import java.util.Objects;

/**
 * Concurrent-read recipe manager using atomic publication of immutable generations. Readers take
 * no locks and may retain old snapshots indefinitely. Writers serialize full replacement; a failed
 * rebuild leaves the previous generation intact. Custom data/matchers must satisfy RecipeType's
 * immutable, reentrant contract: copying a map does not make arbitrary user recipes thread-safe.
 */
public final class RecipeManager {
    /**
     * Safely published complete generation; never mutated after assignment.
     */
    private volatile RecipeSnapshot snapshot = new RecipeSnapshot(0, ObjectArrayView.of());

    /**
     * Captures exactly one coherent recipe generation for a worker or an entire ECS batch.
     *
     * @return immutable snapshot that remains readable across reloads
     */
    public RecipeSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Rebuilds private structures then atomically publishes the complete new generation.
     * Never mutate source values after publication. This does not mutate Minecraft's recipe manager.
     *
     * @param recipes detached entries with globally unique ids and shared category tokens
     * @return newly published generation
     * @throws IllegalArgumentException for duplicate ids or conflicting category tokens
     */
    public synchronized RecipeSnapshot replaceAll(Collection<? extends RecipeEntry<?>> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        var next = new RecipeSnapshot(Math.incrementExact(snapshot.generation()), recipes);
        snapshot = next;
        return next;
    }

    /**
     * Tests snapshot identity, including across managers whose generation numbers happen to match.
     * For world application call this on the owner thread, where native reload publication also runs.
     * It is an observation, not a transaction with concurrent standalone writers.
     *
     * @param expected previously captured snapshot
     * @return true only while that exact generation remains current in this manager
     */
    public boolean isCurrent(RecipeSnapshot expected) {
        return snapshot == expected;
    }

    /**
     * Publishes an empty generation; readers retaining an older snapshot can still finish safely.
     *
     * @return newly published empty generation
     */
    public RecipeSnapshot clear() {
        return replaceAll(ObjectArrayView.of());
    }

    /**
     * Convenience query capturing a generation for one call. For a multi-step job use snapshot().
     *
     * @param type  shared category token
     * @param input detached query data
     * @param <I>   query input type
     * @param <R>   immutable recipe data type
     * @return first matching entry by id, or null
     */
    public <I, R> RecipeEntry<R> first(RecipeType<I, R> type, I input) {
        return snapshot.first(type, input);
    }
}
