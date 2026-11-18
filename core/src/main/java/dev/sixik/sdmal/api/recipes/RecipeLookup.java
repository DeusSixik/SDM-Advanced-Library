package dev.sixik.sdmal.api.recipes;

import dev.sixik.sdmal.core.utils.ObjectArrayView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable typed category within one recipe generation. Cache this object for repeated job queries.
 * Entries and candidate arrays are private and never modified after publication. The caller owns
 * input/result buffers and must obey its RecipeType's pure matching contract.
 *
 * @param <I> detached input type
 * @param <R> immutable prepared data type
 */
public final class RecipeLookup<I, R> {
    /**
     * Shared empty candidate storage; never exposed.
     */
    private static final RecipeEntry<?>[] EMPTY = new RecipeEntry<?>[0];
    /**
     * Shared category matcher and optional key extractors.
     */
    private final RecipeType<I, R> type;
    /**
     * Deterministically sorted complete category entries.
     */
    private final RecipeEntry<R>[] entries;
    /**
     * Optional immutable-after-build primitive candidate map.
     */
    private final Int2ObjectOpenHashMap<RecipeEntry<R>[]> candidates;
    /**
     * Safe read-only facade hiding internal array storage.
     */
    private final ObjectList<RecipeEntry<R>> view;

    /**
     * Builds immutable lookup structures before their snapshot becomes visible to readers.
     *
     * @param type   shared typed category
     * @param sorted entries sorted by full recipe id
     */
    @SuppressWarnings("unchecked")
    RecipeLookup(RecipeType<I, R> type, Collection<RecipeEntry<?>> sorted) {
        this.type = type;
        entries = sorted.toArray(RecipeEntry[]::new);
        view = ObjectArrayView.copyOf(entries).readOnly();
        if (!type.indexed()) {
            candidates = null;
            return;
        }
        var building = new Int2ObjectOpenHashMap<ObjectArrayList<RecipeEntry<R>>>();
        var seen = new IntOpenHashSet();
        for (int index = 0; index < entries.length; index++) {
            RecipeEntry<R> recipe = entries[index];
            int[] keys = type.keys(recipe.value());
            seen.clear();
            for (int key : keys) {
                if (!seen.add(key)) {
                    continue;
                }
                building.computeIfAbsent(key, ignored -> new ObjectArrayList<>()).add(recipe);
            }
        }
        candidates = new Int2ObjectOpenHashMap<>(building.size());
        for (var entry : building.int2ObjectEntrySet()) {
            candidates.put(entry.getIntKey(), entry.getValue().toArray(RecipeEntry[]::new));
        }
    }

    /**
     * Finds the first match in ascending recipe-id order without allocating a result collection.
     *
     * @param input non-null independently owned query data
     * @return matching immutable entry, or null
     */
    public RecipeEntry<R> first(I input) {
        RecipeEntry<R>[] selected = select(input);
        for (int index = 0; index < selected.length; index++) {
            RecipeEntry<R> recipe = selected[index];
            if (type.matches(recipe.value(), input)) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * Appends matches to a caller-owned buffer; it is not cleared or shared by this lookup.
     *
     * @param input  independently owned query data
     * @param output mutable buffer owned by the calling thread
     * @return number of appended matches
     */
    public int findAll(I input, Collection<? super RecipeEntry<R>> output) {
        Objects.requireNonNull(output, "output");
        RecipeEntry<R>[] selected = select(input);
        int found = 0;
        for (int index = 0; index < selected.length; index++) {
            RecipeEntry<R> recipe = selected[index];
            if (!type.matches(recipe.value(), input)) {
                continue;
            }
            output.add(recipe);
            found++;
        }
        return found;
    }

    /**
     * Visits matches on the caller's thread without building an intermediate list.
     *
     * @param input  independently owned query input
     * @param output callback safe for this caller; never retained by the lookup
     * @return number of visited matches
     */
    public int forEachMatch(I input, Consumer<? super RecipeEntry<R>> output) {
        Objects.requireNonNull(output, "output");
        RecipeEntry<R>[] selected = select(input);
        int found = 0;
        for (int index = 0; index < selected.length; index++) {
            RecipeEntry<R> recipe = selected[index];
            if (!type.matches(recipe.value(), input)) {
                continue;
            }
            output.accept(recipe);
            found++;
        }
        return found;
    }

    /**
     * @return immutable category entries, sorted by id, without access to backing arrays
     */
    public ObjectList<RecipeEntry<R>> entries() {
        return view;
    }

    /**
     * @return number of recipes in this captured category
     */
    public int size() {
        return entries.length;
    }

    /**
     * Selects complete candidates without touching mutable native Minecraft data.
     *
     * @param input non-null query input
     * @return private immutable candidate array
     */
    @SuppressWarnings("unchecked")
    private RecipeEntry<R>[] select(I input) {
        Objects.requireNonNull(input, "input");
        if (candidates == null) {
            return entries;
        }
        RecipeEntry<R>[] selected = candidates.get(type.key(input));
        return selected == null ? (RecipeEntry<R>[]) EMPTY : selected;
    }
}
