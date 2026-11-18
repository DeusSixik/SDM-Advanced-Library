package dev.sixik.sdmal.api.recipes;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Typed search contract for immutable prepared recipe data. Keep one shared token per id. Matching
 * must be pure and safe for concurrent calls; inputs belong to the calling job. No live Level,
 * inventory, registry-tag lookup, or mutable native Recipe belongs in this contract.
 *
 * @param <I> independently owned query input
 * @param <R> immutable recipe data
 */
public final class RecipeType<I, R> {
    /**
     * Stable application category id.
     */
    private final String id;
    /**
     * Pure matching operation shared by readers.
     */
    private final BiPredicate<? super R, ? super I> matcher;
    /**
     * Optional primitive candidate key extracted from a detached input.
     */
    private final ToIntFunction<? super I> inputKey;
    /**
     * Optional exhaustive candidate keys for each recipe. Empty keys mean no possible match.
     */
    private final Function<? super R, int[]> recipeKeys;

    /**
     * Creates a typed immutable search contract.
     *
     * @param id         namespaced category id
     * @param matcher    pure reentrant match operation
     * @param inputKey   optional candidate-key extractor
     * @param recipeKeys matching recipe-key extractor, or null together with inputKey
     */
    private RecipeType(String id, BiPredicate<? super R, ? super I> matcher,
                       ToIntFunction<? super I> inputKey, Function<? super R, int[]> recipeKeys) {
        this.id = RecipeEntry.validateId(id);
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.inputKey = inputKey;
        this.recipeKeys = recipeKeys;
    }

    /**
     * Creates a linear typed search over just this category.
     *
     * @param id      namespaced category id
     * @param matcher pure concurrent matching operation
     * @param <I>     query input type
     * @param <R>     immutable prepared data type
     * @return shared category token
     */
    public static <I, R> RecipeType<I, R> of(String id, BiPredicate<? super R, ? super I> matcher) {
        return new RecipeType<>(id, matcher, null, null);
    }

    /**
     * Creates a primitive-key candidate index. Every recipe that can match an input MUST advertise
     * that input's key. Keys narrow candidates; the matcher still checks the full detached input.
     * Recipes with no keys are not candidates. Returned key arrays are consumed only during rebuild.
     *
     * @param id         namespaced category id
     * @param matcher    pure concurrent matching operation
     * @param inputKey   pure query-key function
     * @param recipeKeys exhaustive recipe-key function, returning a non-null array
     * @param <I>        query input type
     * @param <R>        immutable prepared data type
     * @return shared indexed category token
     */
    public static <I, R> RecipeType<I, R> indexed(String id, BiPredicate<? super R, ? super I> matcher,
                                                  ToIntFunction<? super I> inputKey, Function<? super R, int[]> recipeKeys) {
        return new RecipeType<>(id, matcher, Objects.requireNonNull(inputKey, "inputKey"),
                Objects.requireNonNull(recipeKeys, "recipeKeys"));
    }

    /**
     * @return stable namespaced category id
     */
    public String id() {
        return id;
    }

    /**
     * @param recipe immutable data
     * @param input  owned query input
     * @return whether it matches
     */
    boolean matches(R recipe, I input) {
        return matcher.test(recipe, input);
    }

    /**
     * @return whether primitive candidate indexing is enabled
     */
    boolean indexed() {
        return inputKey != null;
    }

    /**
     * @param input owned query input
     * @return primitive candidate key
     */
    int key(I input) {
        return inputKey.applyAsInt(input);
    }

    /**
     * @param recipe immutable data
     * @return exhaustive rebuild-time candidate keys
     */
    int[] keys(R recipe) {
        return Objects.requireNonNull(recipeKeys.apply(recipe), "recipe keys");
    }
}
