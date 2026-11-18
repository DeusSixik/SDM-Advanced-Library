package dev.sixik.sdmal.api.recipes;

import dev.sixik.sdmal.core.utils.ObjectArrayView;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * One immutable recipe generation. Hold this snapshot through capture/compute/apply, then use
 * RecipeManager.isCurrent before changing live inventories. Reload never mutates old generations.
 */
public final class RecipeSnapshot {
    /**
     * Monotonically increasing generation within its owning manager, initially zero.
     */
    private final long generation;
    /**
     * Private id index, immutable after safe publication.
     */
    private final Object2ObjectOpenHashMap<String, RecipeEntry<?>> byId;
    /**
     * Category token identity index, immutable after construction.
     */
    private final Reference2ObjectOpenHashMap<RecipeType<?, ?>, RecipeLookup<?, ?>> lookups;
    /**
     * Safe complete entry view without exposed arrays.
     */
    private final ObjectList<RecipeEntry<?>> entries;

    /**
     * Builds all indices before the manager atomically replaces its published reference.
     *
     * @param generation monotonic manager-local generation
     * @param source     detached immutable entries; collection ownership is not retained
     */
    RecipeSnapshot(long generation, Collection<? extends RecipeEntry<?>> source) {
        this.generation = generation;
        var sorted = new ObjectArrayList<RecipeEntry<?>>(source.size());
        for (var recipe : source) {
            sorted.add(Objects.requireNonNull(recipe, "recipe"));
        }
        sorted.sort(Comparator.comparing(RecipeEntry::id));
        byId = new Object2ObjectOpenHashMap<>(sorted.size());
        var categories = new Object2ObjectOpenHashMap<String, RecipeType<?, ?>>();
        var groups = new Reference2ObjectOpenHashMap<RecipeType<?, ?>, ObjectArrayList<RecipeEntry<?>>>();
        for (int index = 0; index < sorted.size(); index++) {
            RecipeEntry<?> recipe = sorted.get(index);
            if (byId.putIfAbsent(recipe.id(), recipe) != null) {
                throw new IllegalArgumentException("Duplicate prepared recipe id: " + recipe.id());
            }
            var previous = categories.putIfAbsent(recipe.type().id(), recipe.type());
            if (previous != null && previous != recipe.type()) {
                throw new IllegalArgumentException("Use one shared RecipeType token for " + recipe.type().id());
            }
            groups.computeIfAbsent(recipe.type(), ignored -> new ObjectArrayList<>()).add(recipe);
        }
        lookups = new Reference2ObjectOpenHashMap<>(groups.size());
        for (var group : groups.entrySet()) {
            lookups.put(group.getKey(), buildLookup(group.getKey(), group.getValue()));
        }
        entries = ObjectArrayView.copyOf(sorted).readOnly();
    }

    /**
     * @return monotonic manager-local generation, initially zero
     */
    public long generation() {
        return generation;
    }

    /**
     * @return total prepared entries in this generation
     */
    public int size() {
        return byId.size();
    }

    /**
     * @return all immutable entries in ascending recipe-id order
     */
    public ObjectList<RecipeEntry<?>> entries() {
        return entries;
    }

    /**
     * @param id recipe id
     * @return prepared entry, or null when absent
     */
    public RecipeEntry<?> byId(String id) {
        return byId.get(Objects.requireNonNull(id, "id"));
    }

    /**
     * Resolves a prepared typed category without creating an empty lookup on each miss.
     *
     * @param type shared category token
     * @param <I>  query input type
     * @param <R>  immutable recipe data type
     * @return captured category, or null if this generation has no recipes of that type
     */
    @SuppressWarnings("unchecked")
    public <I, R> RecipeLookup<I, R> lookup(RecipeType<I, R> type) {
        return (RecipeLookup<I, R>) lookups.get(Objects.requireNonNull(type, "type"));
    }

    /**
     * Finds one match within exactly this generation.
     *
     * @param type  shared category token
     * @param input detached query data
     * @param <I>   query input type
     * @param <R>   immutable recipe data type
     * @return first matching entry by id, or null
     */
    public <I, R> RecipeEntry<R> first(RecipeType<I, R> type, I input) {
        Objects.requireNonNull(input, "input");
        var lookup = lookup(type);
        return lookup == null ? null : lookup.first(input);
    }

    /**
     * Maintains the category/value relationship after grouping wildcard entries by token identity.
     *
     * @param type    shared category token
     * @param entries entries proven to share that exact token
     * @return immutable typed category lookup
     */
    private static RecipeLookup<?, ?> buildLookup(RecipeType<?, ?> type, Collection<RecipeEntry<?>> entries) {
        return new RecipeLookup<>(type, entries);
    }
}
